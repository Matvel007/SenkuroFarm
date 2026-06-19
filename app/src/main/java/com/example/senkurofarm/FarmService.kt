package com.example.senkurofarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.content.pm.ServiceInfo
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class FarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViews = mutableListOf<WebView>()
    private var farmJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayContainer: FrameLayout? = null
    private var farmViewportWidth = 0
    private var farmViewportHeight = 0
    private var recreatingWebViews = false
    private var sessionTimeCommitted = false
    private var startedAt = 0L
    private var completedTicks = 0L
    private var failedTicks = 0
    private var lastCallbackAt = 0L
    private var lastProgressAt = 0L
    private var lastPosition = -1L
    private var lastKnownUrl = ""
    private var lastChapterId = ""
    private var chapterTransitions = 0
    private var webViewCreatedAt = 0L
    private var mangaTitle = "Манга"
    private var mangaUrl = "https://senkuro.me/"
    private var threads = 1
    private var pageDelaySeconds = 3

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFarm("Фарм остановлен из уведомления")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECORD_CARD) {
            val farmIsActive = farmJob?.isActive == true
            val name = intent.getStringExtra(EXTRA_CARD_NAME).orEmpty().ifBlank { "Карточка" }
            val rank = intent.getStringExtra(EXTRA_CARD_RANK).orEmpty().uppercase().ifBlank { "?" }
            recordCollectedCard(name, rank)
            if (!farmIsActive) stopSelf()
            return if (farmIsActive) START_STICKY else START_NOT_STICKY
        }

        restoreOrUpdateConfiguration(intent)
        Log.d(LOG_TAG, "start farm url=$mangaUrl delay=$pageDelaySeconds threads=$threads")
        startFarm()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (farmJob?.isActive == true) {
            saveRunning(true, "Фарм работает")
            updateNotification("работает")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        commitSessionTime()
        farmJob?.cancel()
        releaseWakeLock()
        destroyWebViews()
        removeOverlayContainer()
        saveRunning(false, "Фарм остановлен")
        super.onDestroy()
    }

    private fun startFarm() {
        if (farmJob?.isActive == true) return
        if (!isReaderPageUrl(mangaUrl)) {
            saveRunning(false, "Откройте конкретную главу перед запуском")
            stopSelf()
            return
        }
        startedAt = System.currentTimeMillis()
        sessionTimeCommitted = false
        completedTicks = 0
        failedTicks = 0
        lastCallbackAt = SystemClock.elapsedRealtime()
        lastProgressAt = lastCallbackAt
        lastPosition = -1L
        lastKnownUrl = resumeUrl()
        lastChapterId = chapterId(lastKnownUrl)
        chapterTransitions = 0
        acquireWakeLock()
        saveRunning(true, "Фарм запускается")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("запускается"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("запускается"))
        }
        startWebViews()
        farmJob = scope.launch {
            while (isActive) {
                checkFarmWatchdog()
                mainHandler.post { scrollWebViewsNow() }
                delay(pageDelaySeconds * 1000L)
            }
        }
    }

    private fun stopFarm(message: String) {
        commitSessionTime()
        farmJob?.cancel()
        farmJob = null
        releaseWakeLock()
        destroyWebViews()
        removeOverlayContainer()
        saveRunning(false, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startWebViews() {
        mainHandler.post {
            destroyWebViewsNow()
            val container = ensureOverlayContainer() ?: run {
                stopFarm("Нужно разрешение «Поверх других окон»")
                return@post
            }
            CookieManager.getInstance().setAcceptCookie(true)
            webViewCreatedAt = SystemClock.elapsedRealtime()
            repeat(threads) { index ->
                val webView = WebView(applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(FarmJavascriptBridge(), "SenkuroFarmNative")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            failedTicks = 0
                            saveRunning(true, "Фарм работает: страница загружена")
                            view.evaluateJavascript(backgroundCardCollectorScript(), null)
                            view.evaluateJavascript(scrollStepScript()) { rawResult ->
                                handleScrollResult(index, rawResult)
                            }
                            Log.d(LOG_TAG, "webView[$index] loaded $url")
                        }

                        @TargetApi(Build.VERSION_CODES.O)
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            Log.e(
                                LOG_TAG,
                                "renderer gone crash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}"
                            )
                            scheduleWebViewRecovery("WebView был перезапущен системой")
                            return true
                        }
                    }
                    onResume()
                    resumeTimers()
                    measure(
                        View.MeasureSpec.makeMeasureSpec(
                            farmViewportWidth,
                            View.MeasureSpec.EXACTLY
                        ),
                        View.MeasureSpec.makeMeasureSpec(
                            farmViewportHeight,
                            View.MeasureSpec.EXACTLY
                        )
                    )
                    layout(
                        0,
                        0,
                        farmViewportWidth,
                        farmViewportHeight
                    )
                }
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        farmViewportWidth,
                        farmViewportHeight
                    )
                )
                webViews.add(webView)
                webView.loadUrl(resumeUrl())
            }
            recreatingWebViews = false
        }
    }

    private fun ensureOverlayContainer(): FrameLayout? {
        if (overlayContainer != null) return overlayContainer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return null
        }
        val container = FrameLayout(this).apply {
            alpha = 0.01f
            visibility = View.VISIBLE
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        farmViewportWidth = resources.displayMetrics.widthPixels.coerceAtMost(720)
        farmViewportHeight = resources.displayMetrics.heightPixels.coerceAtMost(1280)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            farmViewportWidth,
            farmViewportHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
        }
        runCatching {
            getSystemService(WindowManager::class.java).addView(container, params)
            overlayContainer = container
        }.onFailure {
            Log.e(LOG_TAG, "overlay failed", it)
        }
        return overlayContainer
    }

    private fun destroyWebViews() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyWebViewsNow()
        } else {
            mainHandler.post { destroyWebViewsNow() }
        }
    }

    private fun destroyWebViewsNow() {
        webViews.toList().forEach { webView ->
            webView.evaluateJavascript(
                "window.__senkuroFarmDispose && window.__senkuroFarmDispose();",
                null
            )
            webView.removeJavascriptInterface("SenkuroFarmNative")
            webView.onPause()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            (webView.parent as? FrameLayout)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }
        webViews.clear()
    }

    private fun removeOverlayContainer() {
        val container = overlayContainer ?: return
        runCatching { getSystemService(WindowManager::class.java).removeView(container) }
        overlayContainer = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$LOG_TAG:FarmWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun scrollWebViewsNow() {
        if (webViews.isEmpty()) return
        webViews.toList().forEachIndexed { index, webView ->
            webView.onResume()
            webView.resumeTimers()
            webView.evaluateJavascript(scrollStepScript()) { rawResult ->
                handleScrollResult(index, rawResult)
            }
        }
    }

    private fun handleScrollResult(index: Int, rawResult: String?) {
        lastCallbackAt = SystemClock.elapsedRealtime()
        val result = runCatching { JSONObject(rawResult.orEmpty()) }.getOrNull()
        if (result?.optBoolean("ok") == true) {
            completedTicks += 1
            failedTicks = 0
            val position = result.optLong("position")
            val max = result.optLong("max")
            val hidden = result.optBoolean("hidden")
            val currentUrl = result.optString("url")
            val currentChapterId = chapterId(currentUrl)
            if (currentUrl != lastKnownUrl || kotlin.math.abs(position - lastPosition) >= 100L) {
                lastProgressAt = lastCallbackAt
                lastKnownUrl = currentUrl
                lastPosition = position
            }
            if (currentChapterId.isNotBlank() && currentChapterId != lastChapterId) {
                if (lastChapterId.isNotBlank()) chapterTransitions += 1
                lastChapterId = currentChapterId
                if (chapterTransitions >= WEBVIEW_RECYCLE_CHAPTERS) {
                    chapterTransitions = 0
                    scheduleWebViewRecovery("Профилактический перезапуск WebView")
                }
            }
            when (result.optString("action")) {
                "card-opened", "card-modal" -> {
                    saveRunning(true, "Найдена карточка, собираю")
                    updateNotification("собирает карточку")
                }
                "card-collected" -> {
                    val cardName = result.optString("cardName").ifBlank { "Карточка" }
                    val cardRank = result.optString("cardRank").uppercase().ifBlank { "?" }
                    recordCollectedCard(cardName, cardRank)
                }
            }
            saveProgress(position, max, currentUrl)
            if (completedTicks % 10L == 0L) {
                Log.d(
                    LOG_TAG,
                    "heartbeat webView=$index tick=$completedTicks position=$position/$max hidden=$hidden"
                )
                saveRunning(true, "Фарм работает: $position/$max")
                updateNotification("работает")
            }
            return
        }

        failedTicks += 1
        Log.w(LOG_TAG, "tick failed webView=$index count=$failedTicks result=$rawResult")
        if (failedTicks >= 3) {
            scheduleWebViewRecovery("Нет ответа от WebView, перезапуск")
        }
    }

    private fun incrementCards(rank: String): Int {
        val preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = preferences.getInt(KEY_CARDS, 0) + 1
        val editor = preferences.edit().putInt(KEY_CARDS, total)
        if (rank in CARD_RANKS) {
            editor.putInt(botRankKey(rank), preferences.getInt(botRankKey(rank), 0) + 1)
        }
        editor.apply()
        return total
    }

    private fun recordCollectedCard(name: String, rank: String) {
        val preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fingerprint = "${name.trim().lowercase()}|${rank.trim().uppercase()}"
        val now = System.currentTimeMillis()
        val duplicate = preferences.getString(KEY_LAST_CARD_FINGERPRINT, "") == fingerprint &&
            now - preferences.getLong(KEY_LAST_CARD_RECORDED_AT, 0L) < CARD_DEDUPE_WINDOW_MS
        if (duplicate) return
        preferences.edit()
            .putString(KEY_LAST_CARD_FINGERPRINT, fingerprint)
            .putLong(KEY_LAST_CARD_RECORDED_AT, now)
            .apply()
        val totalCards = incrementCards(rank)
        saveRunning(true, "Собрана: $name · всего: $totalCards")
        updateNotification("карточка собрана")
        Log.d(LOG_TAG, "card collected name=$name rank=$rank total=$totalCards")
    }

    private inner class FarmJavascriptBridge {
        @JavascriptInterface
        fun onCardCollected(name: String?, rank: String?) {
            mainHandler.post {
                recordCollectedCard(
                    name.orEmpty().ifBlank { "Карточка" },
                    rank.orEmpty().uppercase().ifBlank { "?" }
                )
            }
        }
    }

    private fun saveProgress(position: Long, max: Long, url: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SCROLL_POSITION, position)
            .putLong(KEY_SCROLL_MAX, max)
            .putLong(KEY_COMPLETED_TICKS, completedTicks)
            .putString(KEY_CURRENT_URL, url)
            .putLong(KEY_TOTAL_RUNTIME_MS, currentTotalRuntime())
            .apply()
    }

    private fun currentTotalRuntime(): Long {
        val preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = preferences.getLong(KEY_ACCUMULATED_RUNTIME_MS, 0L)
        val current = if (!sessionTimeCommitted && startedAt > 0L) {
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        } else 0L
        return saved + current
    }

    private fun commitSessionTime() {
        if (sessionTimeCommitted || startedAt <= 0L) return
        val preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = preferences.getLong(KEY_ACCUMULATED_RUNTIME_MS, 0L) +
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        preferences.edit()
            .putLong(KEY_ACCUMULATED_RUNTIME_MS, total)
            .putLong(KEY_TOTAL_RUNTIME_MS, total)
            .apply()
        sessionTimeCommitted = true
    }

    private fun scheduleWebViewRecovery(message: String) {
        if (recreatingWebViews || farmJob?.isActive != true) return
        recreatingWebViews = true
        lastCallbackAt = SystemClock.elapsedRealtime()
        lastProgressAt = lastCallbackAt
        saveRunning(true, message)
        mainHandler.post {
            destroyWebViewsNow()
            startWebViews()
        }
    }

    private fun checkFarmWatchdog() {
        if (farmJob?.isActive != true || recreatingWebViews) return
        if (wakeLock?.isHeld != true) acquireWakeLock()
        val now = SystemClock.elapsedRealtime()
        if (now - webViewCreatedAt >= WEBVIEW_RECYCLE_INTERVAL_MS) {
            scheduleWebViewRecovery("Профилактическая очистка WebView")
            return
        }
        val callbackTimeout = maxOf(90_000L, pageDelaySeconds * 15_000L)
        val progressTimeout = maxOf(4 * 60_000L, pageDelaySeconds * 60_000L)
        when {
            now - lastCallbackAt > callbackTimeout ->
                scheduleWebViewRecovery("WebView не отвечает, восстановление")
            now - lastProgressAt > progressTimeout ->
                scheduleWebViewRecovery("Прокрутка зависла, восстановление")
        }
    }

    private fun resumeUrl(): String {
        val saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_URL, "")
            .orEmpty()
        return saved.takeIf(::isReaderPageUrl)
            ?: mangaUrl
    }

    private fun chapterId(url: String): String =
        Regex("/chapters/([^/]+)").find(url)?.groupValues?.getOrNull(1).orEmpty()

    private fun isReaderPageUrl(url: String): Boolean =
        url.startsWith("https://senkuro.me/") &&
            Regex("/chapters/[^/]+/pages/\\d+").containsMatchIn(url)

    private fun scrollStepScript(): String = """
        (function() {
          try {
            const root = document.scrollingElement || document.documentElement;
            if (!root) {
              return { ok: false, error: 'document-not-ready', url: location.href };
            }
            if (window.__senkuroFarmCardBusy) {
              return {
                ok: true,
                position: window.scrollY,
                max: Math.max(0, root.scrollHeight - window.innerHeight),
                hidden: document.hidden,
                url: location.href,
                action: 'card-busy'
              };
            }
            const modal = document.querySelector('.modal-container-drop');
            if (modal) {
              const cardName =
                modal.querySelector('.collectible-card__front img')?.getAttribute('alt') ||
                modal.querySelector('.collectible-card__back')?.getAttribute('alt') ||
                'Карточка';
              const cardLink = modal.querySelector('a.collectible-card')?.getAttribute('href') || '';
              const rankMatch = cardLink.match(/-((?:sr)|[sabcdef])$/i);
              const cardRank =
                modal.querySelector('[data-rank]')?.getAttribute('data-rank') ||
                rankMatch?.[1]?.toUpperCase() ||
                '?';
              if (window.__senkuroFarmCardState === 'closing') {
                const retryTarget =
                  document.elementFromPoint(
                    Math.floor(window.innerWidth / 2),
                    Math.max(1, window.innerHeight - 12)
                  ) || modal;
                retryTarget.click();
                return {
                  ok: true,
                  position: window.scrollY,
                  max: Math.max(0, root.scrollHeight - window.innerHeight),
                  hidden: document.hidden,
                  url: location.href,
                  action: 'card-closing',
                  cardName: cardName,
                  cardRank: cardRank
                };
              }
              if (window.__senkuroFarmCardState !== 'modal-ready') {
                window.__senkuroFarmCardState = 'modal-ready';
                return {
                  ok: true,
                  position: window.scrollY,
                  max: Math.max(0, root.scrollHeight - window.innerHeight),
                  hidden: document.hidden,
                  url: location.href,
                  action: 'card-modal',
                  cardName: cardName,
                  cardRank: cardRank
                };
              }
              const x = Math.floor(window.innerWidth / 2);
              const y = Math.max(1, window.innerHeight - 12);
              const closeTarget = document.elementFromPoint(x, y) || modal;
              for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
                closeTarget.dispatchEvent(new MouseEvent(type, {
                  bubbles: true,
                  cancelable: true,
                  clientX: x,
                  clientY: y
                }));
              }
              window.__senkuroFarmCardState = 'closing';
              return {
                ok: true,
                position: window.scrollY,
                max: Math.max(0, root.scrollHeight - window.innerHeight),
                hidden: document.hidden,
                url: location.href,
                action: 'card-collected',
                cardName: cardName,
                cardRank: cardRank
              };
            }
            const cardDrop = document.querySelector('img.cards-drop');
            if (cardDrop) {
              window.__senkuroFarmCardState = 'opening';
              cardDrop.click();
              return {
                ok: true,
                position: window.scrollY,
                max: Math.max(0, root.scrollHeight - window.innerHeight),
                hidden: document.hidden,
                url: location.href,
                action: 'card-opened'
              };
            }
            if (window.__senkuroFarmCardState === 'closing') {
              window.__senkuroFarmCardState = 'idle';
            }
            let scroller = window.__senkuroFarmScroller;
            if (!scroller || !scroller.isConnected ||
                scroller.scrollHeight <= scroller.clientHeight + 100) {
              const candidates = [root];
              for (const element of document.querySelectorAll('main, [role="main"], div')) {
                const style = getComputedStyle(element);
                const overflow = style.overflowY;
                if ((overflow === 'auto' || overflow === 'scroll') &&
                    element.scrollHeight > element.clientHeight + 100) {
                  candidates.push(element);
                }
              }
              scroller = candidates[0];
              for (const candidate of candidates) {
                const range = candidate.scrollHeight - candidate.clientHeight;
                const bestRange = scroller.scrollHeight - scroller.clientHeight;
                if (range > bestRange) scroller = candidate;
              }
              window.__senkuroFarmScroller = scroller;
            }
            const isDocument = scroller === root;
            const viewport = isDocument ? window.innerHeight : scroller.clientHeight;
            const position = isDocument ? window.scrollY : scroller.scrollTop;
            const max = Math.max(0, scroller.scrollHeight - viewport);
            const step = Math.max(180, Math.floor(viewport * 0.55));
            const atEnd = position >= max - 40;
            if (atEnd && /\/chapters\/[^/]+\/pages\//.test(location.pathname)) {
              const nextChapter =
                document.querySelector('button[aria-label="Следующая глава"]:not([disabled])') ||
                document.querySelector('.reader-chapters__arrow-right');
              if (nextChapter) {
                nextChapter.click();
                return {
                  ok: true,
                  position: position,
                  max: max,
                  hidden: document.hidden,
                  url: location.href,
                  action: 'next-chapter'
                };
              }
            }
            const target = atEnd ? max : Math.min(max, position + step);
            if (isDocument) window.scrollTo(0, target);
            else scroller.scrollTop = target;
            scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
            window.dispatchEvent(new Event('scroll'));
            return {
              ok: true,
              position: isDocument ? window.scrollY : scroller.scrollTop,
              max: max,
              hidden: document.hidden,
              url: location.href
            };
          } catch (error) {
            return { ok: false, error: String(error), url: location.href };
          }
        })();
    """.trimIndent()

    internal fun backgroundCardCollectorScript(): String = """
        (function() {
          window.__senkuroFarmDispose && window.__senkuroFarmDispose();
          window.__senkuroFarmCardState = window.__senkuroFarmCardState || 'idle';
          window.__senkuroFarmCardBusy = false;
          window.__senkuroFarmCardReported = false;
          const visible = element => {
            if (!element || !element.isConnected) return false;
            const rect = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return rect.width > 3 && rect.height > 3 &&
              style.display !== 'none' && style.visibility !== 'hidden';
          };
          const findDrop = () => [
            ...document.querySelectorAll(
              'img.cards-drop, .cards-drop, [class*="cards-drop"], ' +
              '[class*="CardDrop"]:not([class*="Modal"]), [data-card-drop]'
            )
          ].find(element => visible(element) && !element.closest(
            '.modal-container-drop, [class*="CardDropModal"], [role="dialog"]'
          ));
          const findModal = () => {
            const direct = document.querySelector(
              '.modal-container-drop, [class*="CardDropModal"], [class*="card-drop-modal"]'
            );
            if (direct) return direct;
            return [...document.querySelectorAll('[role="dialog"]')]
              .find(dialog => dialog.querySelector('.collectible-card'));
          };
          const describe = modal => {
            const cardName =
              modal?.querySelector('.collectible-card__front img')?.getAttribute('alt') ||
              modal?.querySelector('.collectible-card img')?.getAttribute('alt') ||
              modal?.querySelector('img[alt]')?.getAttribute('alt') ||
              'Карточка';
            const cardLink = modal?.querySelector('a.collectible-card')?.getAttribute('href') || '';
            const rankMatch = cardLink.match(/-((?:sr)|[sabcdef])$/i);
            const cardRank =
              modal?.querySelector('[data-rank]')?.getAttribute('data-rank') ||
              rankMatch?.[1]?.toUpperCase() ||
              '?';
            return { cardName, cardRank };
          };
          const clickElement = element => {
            const target = element?.closest('button, a, [role="button"]') || element;
            if (!target) return;
            const rect = target.getBoundingClientRect();
            const x = Math.floor(rect.left + rect.width / 2);
            const y = Math.floor(rect.top + rect.height / 2);
            for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
              target.dispatchEvent(new MouseEvent(type, {
                bubbles: true, cancelable: true, clientX: x, clientY: y
              }));
            }
          };
          const closeModal = modal => {
            const info = describe(modal);
            const x = Math.floor(window.innerWidth / 2);
            const y = Math.max(1, window.innerHeight - 12);
            const target = document.elementFromPoint(x, y) || modal;
            for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
              target.dispatchEvent(new MouseEvent(type, {
                bubbles: true, cancelable: true, clientX: x, clientY: y
              }));
            }
            if (!window.__senkuroFarmCardReported) {
              window.__senkuroFarmCardReported = true;
              if (window.SenkuroFarmNative?.onCardCollected) {
                window.SenkuroFarmNative.onCardCollected(info.cardName, info.cardRank);
              }
            }
            window.__senkuroFarmCardState = 'closing';
          };
          const checkCard = function() {
            const modal = findModal();
            if (modal) {
              window.__senkuroFarmCardBusy = true;
              closeModal(modal);
              return;
            }
            if (window.__senkuroFarmCardState === 'closing') {
              window.__senkuroFarmCardState = 'idle';
              window.__senkuroFarmCardBusy = false;
              window.__senkuroFarmCardReported = false;
            }
            const drop = findDrop();
            if (!drop) {
              window.__senkuroFarmCardBusy = false;
              return;
            }
            window.__senkuroFarmCardBusy = true;
            window.__senkuroFarmCardState = 'opening';
            clickElement(drop);
          };
          let queued = false;
          window.__senkuroFarmCardObserver = new MutationObserver(function() {
            if (queued) return;
            queued = true;
            requestAnimationFrame(function() {
              queued = false;
              checkCard();
            });
          });
          window.__senkuroFarmCardObserver.observe(document.documentElement, {
            childList: true,
            subtree: true
          });
          window.__senkuroFarmCardTimer = setInterval(checkCard, 350);
          window.__senkuroFarmDispose = function() {
            window.__senkuroFarmCardObserver?.disconnect();
            clearInterval(window.__senkuroFarmCardTimer);
            window.__senkuroFarmCardObserver = null;
            window.__senkuroFarmCardTimer = null;
            window.__senkuroFarmScroller = null;
          };
          checkCard();
        })();
    """.trimIndent()

    private fun restoreOrUpdateConfiguration(intent: Intent?) {
        val preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (intent != null) {
            mangaTitle = intent.getStringExtra(EXTRA_MANGA_TITLE).orEmpty().ifBlank { "Манга" }
            mangaUrl = intent.getStringExtra(EXTRA_MANGA_URL).orEmpty().ifBlank { "https://senkuro.me/" }
            threads = intent.getIntExtra(EXTRA_THREADS, 1).coerceIn(1, 5)
            pageDelaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 3).coerceAtLeast(1)
            preferences.edit()
                .putString(KEY_MANGA_TITLE, mangaTitle)
                .putString(KEY_MANGA_URL, mangaUrl)
                .putInt(KEY_THREADS, threads)
                .putInt(KEY_DELAY_SECONDS, pageDelaySeconds)
                .putString(KEY_CURRENT_URL, mangaUrl)
                .apply()
        } else {
            mangaTitle = preferences.getString(KEY_MANGA_TITLE, "Манга").orEmpty().ifBlank { "Манга" }
            mangaUrl = preferences.getString(KEY_MANGA_URL, "https://senkuro.me/").orEmpty()
                .ifBlank { "https://senkuro.me/" }
            threads = preferences.getInt(KEY_THREADS, 1).coerceIn(1, 5)
            pageDelaySeconds = preferences.getInt(KEY_DELAY_SECONDS, 3).coerceAtLeast(1)
        }
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, FarmService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Senkuro Farm: $status")
            .setContentText("$mangaTitle · потоков: $threads · шагов: $completedTicks")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Senkuro Farm",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Постоянное уведомление фонового фарма"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun saveRunning(running: Boolean, message: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, running)
            .putLong(KEY_STARTED_AT, if (running) startedAt else 0L)
            .putString(KEY_LAST_MESSAGE, message)
            .apply()
    }

    companion object {
        const val ACTION_START = "com.example.senkurofarm.action.START_FARM"
        const val ACTION_STOP = "com.example.senkurofarm.action.STOP_FARM"
        const val ACTION_RECORD_CARD = "com.example.senkurofarm.action.RECORD_CARD"
        const val EXTRA_MANGA_TITLE = "manga_title"
        const val EXTRA_MANGA_URL = "manga_url"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_DELAY_SECONDS = "delay_seconds"
        const val EXTRA_CARD_NAME = "card_name"
        const val EXTRA_CARD_RANK = "card_rank"

        const val PREFS = "senkuro_farm_service"
        const val KEY_RUNNING = "running"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_LAST_MESSAGE = "last_message"
        const val KEY_SCROLL_POSITION = "scroll_position"
        const val KEY_SCROLL_MAX = "scroll_max"
        const val KEY_COMPLETED_TICKS = "completed_ticks"
        const val KEY_CURRENT_URL = "current_url"
        const val KEY_CARDS = "cards"
        const val KEY_TOTAL_RUNTIME_MS = "total_runtime_ms"
        private const val KEY_MANGA_TITLE = "manga_title"
        private const val KEY_MANGA_URL = "manga_url"
        private const val KEY_THREADS = "threads"
        private const val KEY_DELAY_SECONDS = "delay_seconds"
        private const val KEY_ACCUMULATED_RUNTIME_MS = "accumulated_runtime_ms"
        private const val KEY_LAST_CARD_FINGERPRINT = "last_card_fingerprint"
        private const val KEY_LAST_CARD_RECORDED_AT = "last_card_recorded_at"

        private const val CHANNEL_ID = "senkuro_farm"
        private const val NOTIFICATION_ID = 1001
        private const val LOG_TAG = "SenkuroFarm"
        private const val WEBVIEW_RECYCLE_CHAPTERS = 10
        private const val WEBVIEW_RECYCLE_INTERVAL_MS = 10 * 60_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val CARD_DEDUPE_WINDOW_MS = 15_000L

        val CARD_RANKS = listOf("SR", "S", "A", "B", "C", "D", "F")
        fun botRankKey(rank: String) = "bot_rank_${rank.lowercase()}"
    }
}
