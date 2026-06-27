package com.example.senkurofarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var farmJob: kotlinx.coroutines.Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var startedAt = 0L
    private var sessionTimeCommitted = false
    private var reads = 0L
    private var claims = 0L
    private var drops = 0L
    private var errors = 0L
    private var cooldownUntil = 0L

    private var mangaTitle = "Манга"
    private var mangaId = ""
    private var branchId = ""
    private var chapterId = ""
    private var chapterNumber = 0
    private var token = ""
    private var claimDelaySeconds = 60

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFarm("Фарм остановлен")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START && farmJob?.isActive == true) return START_STICKY

        restoreConfig(intent)
        token = token.ifBlank { SenkuroApi.loadAccessToken(this) }

        if (token.isBlank()) {
            saveRunning(false, "Нет токена авторизации")
            stopSelf()
            return START_NOT_STICKY
        }
        if (mangaId.isBlank() || branchId.isBlank() || chapterId.isBlank()) {
            saveRunning(false, "Не выбрана книга/глава")
            stopSelf()
            return START_NOT_STICKY
        }

        startFarm(reset = intent?.action == ACTION_START)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (farmJob?.isActive == true) saveRunning(true, "Фарм работает")
    }

    override fun onDestroy() {
        commitSessionTime()
        farmJob?.cancel()
        releaseWakeLock()
        saveRunning(false, "Фарм остановлен")
        super.onDestroy()
    }

    private fun startFarm(reset: Boolean) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (reset) {
            reads = 0; claims = 0; drops = 0; errors = 0; cooldownUntil = 0
            prefs.edit()
                .putLong(KEY_READS, 0).putLong(KEY_CLAIMS, 0)
                .putLong(KEY_DROPS, 0).putLong(KEY_ERRORS, 0)
                .putInt(KEY_SESSION_CARDS, 0)
                .apply()
        } else {
            reads = prefs.getLong(KEY_READS, 0)
            claims = prefs.getLong(KEY_CLAIMS, 0)
            drops = prefs.getLong(KEY_DROPS, 0)
            errors = prefs.getLong(KEY_ERRORS, 0)
        }
        startedAt = System.currentTimeMillis()
        sessionTimeCommitted = false
        acquireWakeLock()
        saveRunning(true, "Фарм запускается")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification("запускается"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("запускается"))
        }
        farmJob = scope.launch { loop() }
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            reads++
            saveProgress()
            saveRunning(true, "Чтение главы #$reads")
            updateNotification("читает главу")
            val ok = SenkuroApi.readChapter(token, mangaId, branchId, chapterId)
            if (!ok) {
                errors++
                saveProgress()
                saveRunning(true, "Ошибка чтения")
                updateNotification("ошибка чтения")
                delay(5_000)
                continue
            }

            saveRunning(true, "Окно открыто — собираем карту")
            updateNotification("сбор карты")
            delay(3_000)

            // Окно дропа живёт ~9 минут. Перечитываем через этот интервал.
            val windowDeadline = System.currentTimeMillis() + 540_000L
            var dropped = false
            var lastWakeRefresh = System.currentTimeMillis()
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < windowDeadline) {
                val now = System.currentTimeMillis()
                if (now - lastWakeRefresh > 300_000L) { refreshWakeLock(); lastWakeRefresh = now }
                if (now < cooldownUntil) {
                    delay((cooldownUntil - now).coerceAtMost(15_000))
                    continue
                }
                claims++
                saveProgress()
                when (SenkuroApi.claimCard(token)) {
                    ClaimResult.DROP -> {
                        drops++
                        saveProgress()
                        val totalCards = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CARDS, 0) + 1
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CARDS, totalCards).apply()
                        saveRunning(true, "КАРТА! попытка #$claims")
                        updateNotification("карта!")
                        Log.d(LOG_TAG, "CARD DROP reads=$reads claims=$claims drops=$drops total=$totalCards")
                        dropped = true
                        delay(2_000)
                        break
                    }
                    ClaimResult.NO_CARD -> {
                        saveRunning(true, "Попытка #$claims: карты нет")
                        updateNotification("попытка #$claims")
                    }
                    ClaimResult.COOLDOWN -> {
                        cooldownUntil = System.currentTimeMillis() + 15_000
                        saveRunning(true, "Ожидание перед следующей попыткой")
                        updateNotification("кулдаун")
                    }
                    ClaimResult.ERROR -> {
                        errors++
                        saveProgress()
                        saveRunning(true, "Ошибка сбора")
                        updateNotification("ошибка сбора")
                    }
                }
                saveProgress()
                delay(claimDelaySeconds * 1000L)
            }
            Log.d(LOG_TAG, "Re-reading: dropped=$dropped elapsed=${System.currentTimeMillis()-windowDeadline+540_000L}ms")
            refreshWakeLock()
        }
    }

    private fun stopFarm(message: String) {
        commitSessionTime()
        farmJob?.cancel()
        farmJob = null
        releaseWakeLock()
        saveRunning(false, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreConfig(intent: Intent?) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (intent != null) {
            mangaTitle = intent.getStringExtra(EXTRA_MANGA_TITLE).orEmpty().ifBlank { "Манга" }
            mangaId = intent.getStringExtra(EXTRA_MANGA_ID).orEmpty()
            branchId = intent.getStringExtra(EXTRA_BRANCH_ID).orEmpty()
            chapterId = intent.getStringExtra(EXTRA_CHAPTER_ID).orEmpty()
            chapterNumber = intent.getIntExtra(EXTRA_CHAPTER_NUMBER, 0)
            token = intent.getStringExtra(EXTRA_ACCESS_TOKEN).orEmpty()
            claimDelaySeconds = intent.getIntExtra(EXTRA_CLAIM_DELAY_SECONDS, 60).coerceIn(10, 300)
            prefs.edit()
                .putString(KEY_MANGA_TITLE, mangaTitle)
                .putString(KEY_MANGA_ID, mangaId)
                .putString(KEY_BRANCH_ID, branchId)
                .putString(KEY_CHAPTER_ID, chapterId)
                .putInt(KEY_CHAPTER_NUMBER, chapterNumber)
                .putInt(KEY_CLAIM_DELAY_SECONDS, claimDelaySeconds)
                .apply()
        } else {
            mangaTitle = prefs.getString(KEY_MANGA_TITLE, "Манга").orEmpty()
            mangaId = prefs.getString(KEY_MANGA_ID, "").orEmpty()
            branchId = prefs.getString(KEY_BRANCH_ID, "").orEmpty()
            chapterId = prefs.getString(KEY_CHAPTER_ID, "").orEmpty()
            chapterNumber = prefs.getInt(KEY_CHAPTER_NUMBER, 0)
            claimDelaySeconds = prefs.getInt(KEY_CLAIM_DELAY_SECONDS, 60)
        }
    }

    private fun saveProgress() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Периодически коммитим сессию в accumulated, чтобы не потерять при убийстве процесса
        if (!sessionTimeCommitted && startedAt > 0L) {
            val now = System.currentTimeMillis()
            val sessionDelta = (now - startedAt).coerceAtLeast(0L)
            if (sessionDelta > 60_000L) {
                val acc = prefs.getLong(KEY_ACCUMULATED_RUNTIME_MS, 0L) + sessionDelta
                prefs.edit().putLong(KEY_ACCUMULATED_RUNTIME_MS, acc).apply()
                startedAt = now
            }
        }
        prefs.edit()
            .putLong(KEY_READS, reads)
            .putLong(KEY_CLAIMS, claims)
            .putLong(KEY_DROPS, drops)
            .putLong(KEY_ERRORS, errors)
            .putInt(KEY_SESSION_CARDS, drops.toInt())
            .putLong(KEY_TOTAL_RUNTIME_MS, currentTotalRuntime())
            .apply()
    }

    private fun saveRunning(running: Boolean, message: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, running)
            .putLong(KEY_STARTED_AT, if (running) startedAt else 0L)
            .putString(KEY_LAST_MESSAGE, message)
            .apply()
    }

    private fun currentTotalRuntime(): Long {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getLong(KEY_ACCUMULATED_RUNTIME_MS, 0L)
        val current = if (!sessionTimeCommitted && startedAt > 0L) (System.currentTimeMillis() - startedAt).coerceAtLeast(0) else 0L
        return saved + current
    }

    private fun commitSessionTime() {
        if (sessionTimeCommitted || startedAt <= 0L) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = prefs.getLong(KEY_ACCUMULATED_RUNTIME_MS, 0L) + (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
        prefs.edit().putLong(KEY_ACCUMULATED_RUNTIME_MS, total).putLong(KEY_TOTAL_RUNTIME_MS, total).apply()
        sessionTimeCommitted = true
    }

    private fun acquireWakeLock() {
        wakeLock = wakeLock?.takeIf { it.isHeld }
            ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOG_TAG:FarmWakeLock")
                .apply { setReferenceCounted(false) }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(60 * 60_000L)
        }
    }

    private fun refreshWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock?.acquire(60 * 60_000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val stopPi = PendingIntent.getService(this, 1, Intent(this, FarmService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Senkuro Farm: $status")
            .setContentText("$mangaTitle §$chapterNumber · прочитано:$reads попыток:$claims карт:$drops")
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stopPi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Senkuro Farm", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_START = "com.example.senkurofarm.action.START_FARM"
        const val ACTION_STOP = "com.example.senkurofarm.action.STOP_FARM"
        const val EXTRA_MANGA_TITLE = "manga_title"
        const val EXTRA_MANGA_ID = "manga_id"
        const val EXTRA_BRANCH_ID = "branch_id"
        const val EXTRA_CHAPTER_ID = "chapter_id"
        const val EXTRA_CHAPTER_NUMBER = "chapter_number"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_CLAIM_DELAY_SECONDS = "claim_delay_seconds"

        // legacy constants to keep old code compilable if referenced
        const val EXTRA_MANGA_URL = "manga_url"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_DELAY_SECONDS = "delay_seconds"
        const val EXTRA_EXPERIMENTAL_MODE = "experimental_mode"
        const val EXTRA_LOOP_MODE = "loop_mode"
        const val ACTION_RECORD_CARD = "com.example.senkurofarm.action.RECORD_CARD"
        const val EXTRA_CARD_NAME = "card_name"
        const val EXTRA_CARD_RANK = "card_rank"

        const val PREFS = "senkuro_farm_service"
        const val KEY_RUNNING = "running"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_LAST_MESSAGE = "last_message"
        const val KEY_READS = "reads"
        const val KEY_CLAIMS = "claims"
        const val KEY_DROPS = "drops"
        const val KEY_ERRORS = "errors"
        const val KEY_SESSION_CARDS = "session_cards"
        const val KEY_TOTAL_RUNTIME_MS = "total_runtime_ms"
        const val KEY_CARDS = "cards"
        const val KEY_SCROLL_POSITION = "scroll_position"
        const val KEY_SCROLL_MAX = "scroll_max"
        const val KEY_COMPLETED_TICKS = "completed_ticks"
        const val KEY_CURRENT_URL = "current_url"

        private const val KEY_MANGA_TITLE = "manga_title"
        private const val KEY_MANGA_ID = "manga_id"
        private const val KEY_BRANCH_ID = "branch_id"
        private const val KEY_CHAPTER_ID = "chapter_id"
        private const val KEY_CHAPTER_NUMBER = "chapter_number"
        private const val KEY_CLAIM_DELAY_SECONDS = "claim_delay_seconds"
        private const val KEY_ACCUMULATED_RUNTIME_MS = "accumulated_runtime_ms"

        private const val CHANNEL_ID = "senkuro_farm"
        private const val NOTIFICATION_ID = 1001
        private const val LOG_TAG = "SenkuroFarm"
        val CARD_RANKS = listOf("SR", "S", "A", "B", "C", "D", "F")
        fun botRankKey(rank: String) = "bot_rank_${rank.lowercase()}"
    }
}
