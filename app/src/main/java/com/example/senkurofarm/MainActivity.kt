package com.example.senkurofarm

import android.Manifest
import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CookieManager.getInstance().setAcceptCookie(true)
        restoreSenkuroCookies(this)
        setContent { SenkuroFarmApp() }
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    Home("Карты", Icons.Rounded.Home),
    Farm("Фарм", Icons.Rounded.FlashOn),
    Statistics("Статы", Icons.Rounded.QueryStats),
    Settings("Настройки", Icons.Rounded.Settings),
    Info("Инфо", Icons.Rounded.Info)
}

private data class CardItem(
    val title: String,
    val rank: String,
    val imageUrl: String,
    val count: Int = 1,
    val isShard: Boolean = false,
    val modifier: Int = 0
)

private data class MangaItem(val title: String, val url: String)

private data class FarmProgress(
    val position: Long = 0,
    val max: Long = 0,
    val ticks: Long = 0,
    val sessionCards: Int = 0,
    val url: String = ""
)

private data class FarmStatistics(
    val runtimeMs: Long = 0,
    val botCards: Int = 0,
    val botRanks: Map<String, Int> = emptyMap()
)

private data class CardsPage(
    val cards: List<CardItem>,
    val endCursor: String?,
    val hasNextPage: Boolean
)

private data class CachedCardsPage(val page: CardsPage, val savedAt: Long)

@Keep
internal class FarmWebBridge(
    private val mainHandler: Handler,
    private val onUrlChanged: (String) -> Unit,
    private val onCardCollected: (String, String) -> Unit
) {
    @JavascriptInterface
    fun onUrlChanged(url: String?) {
        url?.takeIf { it.isNotBlank() }?.let { value ->
            mainHandler.post { onUrlChanged(value) }
        }
    }

    @JavascriptInterface
    fun onCardCollected(name: String?, rank: String?) {
        mainHandler.post {
            onCardCollected(
                name.orEmpty().ifBlank { "Карточка" },
                rank.orEmpty().uppercase().ifBlank { "?" }
            )
        }
    }
}

private const val CARDS_PAGE_SIZE = 20
private const val GRAPHQL_URL = "https://api.senkuro.me/graphql"
private const val CARDS_CACHE_TTL_MS = 10 * 60 * 1000L
private const val VISIBLE_FARM_SYNC_INTERVAL_MS = 3_000L
private val networkClient = OkHttpClient()
private val graphQlUserIdCache = ConcurrentHashMap<String, String>()

private val darkScheme = darkColorScheme(
    primary = Color(0xFF9D7BFF),
    secondary = Color(0xFFFFC857),
    tertiary = Color(0xFF5EEAD4),
    background = Color(0xFF0C0D12),
    surface = Color(0xFF151720),
    surfaceVariant = Color(0xFF202330)
)

private val lightScheme = lightColorScheme(
    primary = Color(0xFF6146C6),
    secondary = Color(0xFF9A6400),
    tertiary = Color(0xFF00796B),
    background = Color(0xFFF6F4FF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9E4F8)
)

@Composable
private fun SenkuroFarmApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var darkTheme by remember { mutableStateOf(true) }
    var authorized by remember { mutableStateOf(hasSenkuroSession(context)) }
    var showLicense by remember { mutableStateOf(false) }
    var showSenkuroWeb by remember { mutableStateOf(false) }
    var savedProfileUrl by remember { mutableStateOf(loadSavedProfileUrl(context)) }
    var shellReloadKey by remember { mutableIntStateOf(0) }
    var availableUpdate by remember { mutableStateOf<GithubRelease?>(null) }
    var pendingInstallPermission by remember { mutableStateOf<GithubRelease?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadedBytes by remember { mutableStateOf(0L) }
    var updateTotalBytes by remember { mutableStateOf(0L) }
    var updateNotice by remember { mutableStateOf<String?>(null) }

    val startUpdateDownload: (GithubRelease) -> Unit = { release ->
        if (!downloadingUpdate) {
            scope.launch {
                downloadingUpdate = true
                updateDownloadedBytes = 0L
                updateTotalBytes = 0L
                runCatching {
                    AppUpdater.downloadAndVerify(context, release) { downloaded, total ->
                        updateDownloadedBytes = downloaded
                        updateTotalBytes = total
                    }
                }.onSuccess { apk ->
                    availableUpdate = null
                    AppUpdater.install(context, apk)
                }.onFailure {
                    updateNotice = "Не удалось установить обновление: ${it.message.orEmpty()}"
                }
                downloadingUpdate = false
            }
        }
    }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val release = pendingInstallPermission
        pendingInstallPermission = null
        if (release != null && AppUpdater.canInstallPackages(context)) {
            startUpdateDownload(release)
        } else if (release != null) {
            updateNotice = "Без разрешения на установку приложений обновление невозможно."
        }
    }

    LaunchedEffect(Unit) {
        runCatching { AppUpdater.findUpdate(context) }
            .onSuccess { release ->
                if (release != null) availableUpdate = release
            }
    }

    MaterialTheme(colorScheme = if (darkTheme) darkScheme else lightScheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                when {
                    !authorized -> LoginScreen(onLoggedIn = { profileUrl ->
                        saveSenkuroCookies(context)
                        saveAuthorized(context, true)
                        if (profileUrl.isNotBlank()) {
                            val normalizedProfile = normalizeProfileUrl(profileUrl)
                            savedProfileUrl = normalizedProfile
                            saveProfileUrl(context, normalizedProfile)
                        }
                        authorized = true
                        shellReloadKey += 1
                    })
                    showSenkuroWeb -> LoginScreen(
                        startInWeb = true,
                        title = "Senkuro",
                        doneText = "Назад в приложение",
                        onLoggedIn = { profileUrl ->
                            saveSenkuroCookies(context)
                            saveAuthorized(context, true)
                            if (profileUrl.isNotBlank()) {
                                val normalizedProfile = normalizeProfileUrl(profileUrl)
                                savedProfileUrl = normalizedProfile
                                saveProfileUrl(context, normalizedProfile)
                            }
                            authorized = true
                            showSenkuroWeb = false
                            shellReloadKey += 1
                        }
                    )
                    showLicense -> LicenseScreen(onBack = { showLicense = false })
                    else -> MainShell(
                        initialProfileUrl = savedProfileUrl,
                        externalReloadKey = shellReloadKey,
                        darkTheme = darkTheme,
                        onDarkThemeChange = { darkTheme = it },
                        onProfileUrlSaved = {
                            val normalizedProfile = normalizeProfileUrl(it)
                            savedProfileUrl = normalizedProfile
                            saveProfileUrl(context, normalizedProfile)
                        },
                        onOpenSession = { showSenkuroWeb = true },
                        onOpenLicense = { showLicense = true },
                        currentVersion = AppUpdater.currentVersion(context),
                        checkingUpdate = checkingUpdate,
                        onCheckUpdate = {
                            if (!checkingUpdate) {
                                scope.launch {
                                    checkingUpdate = true
                                    runCatching { AppUpdater.findUpdate(context) }
                                        .onSuccess { release ->
                                            if (release == null) {
                                                updateNotice =
                                                    "Установлена актуальная версия ${AppUpdater.currentVersion(context)}."
                                            } else {
                                                availableUpdate = release
                                            }
                                        }
                                        .onFailure {
                                            updateNotice =
                                                "Не удалось проверить обновления: ${it.message.orEmpty()}"
                                        }
                                    checkingUpdate = false
                                }
                            }
                        }
                    )
                }
            }

            availableUpdate?.let { release ->
                AlertDialog(
                    onDismissRequest = {
                        if (!downloadingUpdate) availableUpdate = null
                    },
                    title = { Text("Доступно обновление ${release.version}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(release.title, fontWeight = FontWeight.Bold)
                            Text(
                                release.notes,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                            if (downloadingUpdate) {
                                val progress = if (updateTotalBytes > 0L) {
                                    (updateDownloadedBytes.toFloat() / updateTotalBytes)
                                        .coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    if (updateTotalBytes > 0L) {
                                        val downloadedMb = updateDownloadedBytes / 1_048_576f
                                        val totalMb = updateTotalBytes / 1_048_576f
                                        "Загружено %.1f из %.1f МБ · %d%%".format(
                                            downloadedMb,
                                            totalMb,
                                            (progress * 100).toInt()
                                        )
                                    } else {
                                        "Подключение к GitHub…"
                                    }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            enabled = !downloadingUpdate,
                            onClick = {
                                if (AppUpdater.canInstallPackages(context)) {
                                    startUpdateDownload(release)
                                } else {
                                    pendingInstallPermission = release
                                    unknownSourcesLauncher.launch(
                                        AppUpdater.unknownSourcesIntent(context)
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Обновить")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !downloadingUpdate,
                            onClick = { availableUpdate = null }
                        ) { Text("Позже") }
                    }
                )
            }

            updateNotice?.let { message ->
                AlertDialog(
                    onDismissRequest = { updateNotice = null },
                    title = { Text("Обновления") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { updateNotice = null }) { Text("Хорошо") }
                    }
                )
            }
        }
    }
}

private fun hasSenkuroSession(context: Context): Boolean {
    restoreSenkuroCookies(context)
    if (loadAuthorized(context)) return true
    val cookie = CookieManager.getInstance().getCookie("https://senkuro.me").orEmpty()
    val backup = loadSenkuroCookieBackup(context)
    val combined = "$cookie;$backup".lowercase()
    return combined.isNotBlank() && listOf("auth", "token", "session", "access", "refresh", "senkuro").any { it in combined }
}

private fun loadAuthorized(context: Context): Boolean =
    context.getSharedPreferences("senkuro_farm", Context.MODE_PRIVATE).getBoolean("authorized", false)

private fun saveAuthorized(context: Context, authorized: Boolean) {
    context.getSharedPreferences("senkuro_farm", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("authorized", authorized)
        .apply()
}

private fun loadSavedProfileUrl(context: Context): String =
    normalizeProfileUrl(context.getSharedPreferences("senkuro_farm", Context.MODE_PRIVATE).getString("profile_url", "").orEmpty()).takeIf {
        it.contains("/users/")
    }.orEmpty()

private fun saveProfileUrl(context: Context, profileUrl: String) {
    val normalizedProfile = normalizeProfileUrl(profileUrl).takeIf { it.contains("/users/") }.orEmpty()
    context.getSharedPreferences("senkuro_farm", Context.MODE_PRIVATE)
        .edit()
        .putString("profile_url", normalizedProfile)
        .apply()
}

private fun loadSenkuroCookieBackup(context: Context): String =
    context.getSharedPreferences("senkuro_farm", Context.MODE_PRIVATE).getString("senkuro_cookies", "").orEmpty()

private fun saveSenkuroCookieBackup(context: Context, cookies: String) {
    context.getSharedPreferences("senkuro_farm", Context.MODE_PRIVATE)
        .edit()
        .putString("senkuro_cookies", cookies)
        .apply()
}

private fun saveSenkuroCookies(context: Context) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()
    val cookies = cookieManager.getCookie("https://senkuro.me").orEmpty()
    if (cookies.isNotBlank()) saveSenkuroCookieBackup(context, cookies)
}

private fun restoreSenkuroCookies(context: Context) {
    val cookies = loadSenkuroCookieBackup(context)
    if (cookies.isBlank()) return
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookies.split(';')
        .map { it.trim() }
        .filter { it.contains('=') }
        .forEach { cookie ->
            cookieManager.setCookie("https://senkuro.me", cookie)
            cookieManager.setCookie("https://api.senkuro.me", cookie)
        }
    cookieManager.flush()
}

private fun startFarmService(context: Context, mangaUrl: String, threads: Int, delaySeconds: Int) {
    val intent = Intent(context, FarmService::class.java)
        .setAction(FarmService.ACTION_START)
        .putExtra(FarmService.EXTRA_MANGA_TITLE, "Книга")
        .putExtra(FarmService.EXTRA_MANGA_URL, mangaUrl)
        .putExtra(FarmService.EXTRA_THREADS, threads)
        .putExtra(FarmService.EXTRA_DELAY_SECONDS, delaySeconds)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopFarmService(context: Context) {
    val intent = Intent(context, FarmService::class.java).setAction(FarmService.ACTION_STOP)
    context.startService(intent)
}

private fun recordVisibleCard(context: Context, name: String, rank: String) {
    context.startService(
        Intent(context, FarmService::class.java)
            .setAction(FarmService.ACTION_RECORD_CARD)
            .putExtra(FarmService.EXTRA_CARD_NAME, name)
            .putExtra(FarmService.EXTRA_CARD_RANK, rank)
    )
}

private fun isFarmServiceRunning(context: Context): Boolean =
    context.getSharedPreferences(FarmService.PREFS, Context.MODE_PRIVATE)
        .getBoolean(FarmService.KEY_RUNNING, false) && isFarmServiceProcessRunning(context)

private fun isFarmServiceProcessRunning(context: Context): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java)
    return manager.getRunningServices(Int.MAX_VALUE).any { service ->
        service.service.className == FarmService::class.java.name
    }
}

private fun loadFarmServiceMessage(context: Context): String =
    context.getSharedPreferences(FarmService.PREFS, Context.MODE_PRIVATE)
        .getString(FarmService.KEY_LAST_MESSAGE, "")
        .orEmpty()

private fun loadFarmProgress(context: Context): FarmProgress {
    val preferences = context.getSharedPreferences(FarmService.PREFS, Context.MODE_PRIVATE)
    return FarmProgress(
        position = preferences.getLong(FarmService.KEY_SCROLL_POSITION, 0),
        max = preferences.getLong(FarmService.KEY_SCROLL_MAX, 0),
        ticks = preferences.getLong(FarmService.KEY_COMPLETED_TICKS, 0),
        sessionCards = preferences.getInt(FarmService.KEY_SESSION_CARDS, 0),
        url = preferences.getString(FarmService.KEY_CURRENT_URL, "").orEmpty().ifBlank {
            preferences.getString(FarmService.EXTRA_MANGA_URL, "").orEmpty()
        }
    )
}

private fun loadFarmStatistics(context: Context): FarmStatistics {
    val preferences = context.getSharedPreferences(FarmService.PREFS, Context.MODE_PRIVATE)
    return FarmStatistics(
        runtimeMs = preferences.getLong(FarmService.KEY_TOTAL_RUNTIME_MS, 0L),
        botCards = preferences.getInt(FarmService.KEY_CARDS, 0),
        botRanks = FarmService.CARD_RANKS.associateWith {
            preferences.getInt(FarmService.botRankKey(it), 0)
        }
    )
}

@Composable
private fun LoginScreen(
    startInWeb: Boolean = false,
    title: String = "Войдите в Senkuro",
    doneText: String = "Я вошёл",
    onLoggedIn: (String) -> Unit
) {
    var showWeb by remember { mutableStateOf(startInWeb) }
    var loginWebView by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    if (showWeb) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = {
                        val webView = loginWebView
                        if (webView == null) {
                            scope.launch { onLoggedIn(discoverProfileUrl()) }
                        } else {
                            webView.evaluateJavascript(profileDiscoveryScript()) { raw ->
                                val fromPage = cleanJsString(raw)
                                if (fromPage.isNotBlank()) {
                                    onLoggedIn(fromPage)
                                } else {
                                    scope.launch { onLoggedIn(discoverProfileUrl()) }
                                }
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                ) { Text(doneText) }
            }
            SenkuroWebView(url = "https://senkuro.me/", onWebViewCreated = { loginWebView = it })
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF080912), Color(0xFF17142D), Color(0xFF261B42))
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF9D7BFF), Color(0xFFFFC857)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Article,
                    contentDescription = null,
                    tint = Color(0xFF111113),
                    modifier = Modifier.size(54.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("Senkuro Farm", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text(
                "Карты, лог, статистика и фоновый фарм в одном минималистичном клиенте.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFD7D1EA),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF211D38)),
                border = BorderStroke(1.dp, Color(0xFF66558C)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Как начать",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "1. Откройте сайт и авторизуйтесь в Senkuro.\n" +
                            "2. После входа нажмите кнопку «Я вошёл» в верхней части экрана.",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE4DFF1),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "ОБЯЗАТЕЛЬНО ВЫДАЙТЕ РАЗРЕШЕНИЕ «ПОВЕРХ ДРУГИХ ОКОН» — " +
                            "БЕЗ НЕГО ФОНОВЫЙ ФАРМ НЕ ЗАПУСТИТСЯ.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFFD166),
                        textAlign = TextAlign.Center
                    )
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                context.startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Выдать разрешение")
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { showWeb = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Rounded.Login, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Войти через Senkuro")
            }
            Text(
                "Пароль не хранится. Используется обычная WebView-сессия сайта.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAFA8C5),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SenkuroWebView(url: String, onWebViewCreated: (WebView) -> Unit = {}) {
    val appContext = LocalContext.current.applicationContext
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            restoreSenkuroCookies(context.applicationContext)
            WebView(context).apply {
                onWebViewCreated(this)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        view.loadUrl(request.url.toString())
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        saveSenkuroCookies(appContext)
                    }
                }
                loadUrl(url)
            }
        }
    )
}

@Composable
private fun AutoReadingWebView(url: String, delaySeconds: Int) {
    val appContext = LocalContext.current.applicationContext
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(520.dp)
            .clip(RoundedCornerShape(18.dp)),
        factory = { context ->
            restoreSenkuroCookies(context.applicationContext)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        view.loadUrl(request.url.toString())
                        return true
                    }

                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        super.onPageFinished(view, loadedUrl)
                        saveSenkuroCookies(appContext)
                        view.evaluateJavascript(autoScrollScript(delaySeconds), null)
                    }
                }
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url != url) webView.loadUrl(url)
            webView.evaluateJavascript(autoScrollScript(delaySeconds), null)
        }
    )
}

private fun autoScrollScript(delaySeconds: Int): String = """
    (function() {
      if (window.__senkuroFarmAutoScroll) clearInterval(window.__senkuroFarmAutoScroll);
      const step = Math.max(180, Math.floor(window.innerHeight * 0.55));
      const delay = ${delaySeconds.coerceAtLeast(1) * 1000};
      window.__senkuroFarmAutoScroll = setInterval(function() {
        const max = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight) - window.innerHeight;
        if (window.scrollY >= max - 20) {
          window.scrollTo({ top: 0, behavior: 'smooth' });
        } else {
          window.scrollBy({ top: step, behavior: 'smooth' });
        }
      }, delay);
    })();
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    initialProfileUrl: String,
    externalReloadKey: Int,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onProfileUrlSaved: (String) -> Unit,
    onOpenSession: () -> Unit,
    onOpenLicense: () -> Unit,
    currentVersion: String,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(Tab.Home) }
    val cards = remember { mutableStateListOf<CardItem>() }
    var isLoadingCards by remember { mutableStateOf(true) }
    var isFarming by remember { mutableStateOf(isFarmServiceRunning(context)) }
    var cardsReloadKey by remember { mutableIntStateOf(0) }
    var profileUrl by remember { mutableStateOf(initialProfileUrl) }
    var profileInput by remember { mutableStateOf(initialProfileUrl) }
    var cardsStatus by remember { mutableStateOf("Ищу профиль и загружаю карточки...") }
    var cardsPage by remember { mutableIntStateOf(1) }
    var hasNextCardsPage by remember { mutableStateOf(false) }
    val cardsPageCursors = remember { mutableStateMapOf<Int, String>() }
    val cardsPageCache = remember { mutableStateMapOf<Int, CachedCardsPage>() }
    var lastFarmMessage by remember { mutableStateOf(loadFarmServiceMessage(context)) }
    var farmProgress by remember { mutableStateOf(loadFarmProgress(context)) }
    var farmStatistics by remember { mutableStateOf(loadFarmStatistics(context)) }
    var accountRankStats by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var accountCardsTotal by remember { mutableIntStateOf(0) }
    var accountStatsLoading by remember { mutableStateOf(false) }

    LaunchedEffect(initialProfileUrl, externalReloadKey) {
        if (initialProfileUrl.isNotBlank() && initialProfileUrl != profileUrl) {
            profileUrl = initialProfileUrl
            profileInput = initialProfileUrl
            cardsPage = 1
            cardsPageCursors.clear()
            cardsPageCache.clear()
        }
        cardsReloadKey += 1
    }

    LaunchedEffect(cardsReloadKey, externalReloadKey, cardsPage) {
        val activeProfileUrl = profileUrl.ifBlank { initialProfileUrl }.ifBlank {
            discoverProfileUrl().also { discovered ->
                if (discovered.isNotBlank()) {
                    profileUrl = discovered
                    profileInput = discovered
                    cardsPage = 1
                    cardsPageCursors.clear()
                    cardsPageCache.clear()
                    onProfileUrlSaved(discovered)
                }
            }
        }
        if (activeProfileUrl.isBlank()) {
            isLoadingCards = false
            cardsStatus = "Не удалось автоматически найти профиль. Вставьте ссылку один раз, потом она сохранится."
        } else {
            val cached = cardsPageCache[cardsPage]
                ?: loadCachedCardsPage(context, activeProfileUrl, cardsPage)?.also {
                    cardsPageCache[cardsPage] = it
                }
            if (cached != null) {
                showCardsPage(
                    target = cards,
                    result = cached.page,
                    page = cardsPage,
                    onStatus = { cardsStatus = it },
                    onHasNext = { hasNextCardsPage = it }
                )
                isLoadingCards = false
            } else {
                isLoadingCards = true
                cards.clear()
            }
            if (cached != null && System.currentTimeMillis() - cached.savedAt < CARDS_CACHE_TTL_MS) {
                return@LaunchedEffect
            }
            val result = loadProfileCards(activeProfileUrl, cardsPage)
            val saved = CachedCardsPage(result, System.currentTimeMillis())
            cardsPageCache[cardsPage] = saved
            saveCachedCardsPage(context, activeProfileUrl, cardsPage, saved)
            result.endCursor?.let { cardsPageCursors[cardsPage] = it }
            showCardsPage(
                target = cards,
                result = result,
                page = cardsPage,
                onStatus = { cardsStatus = it },
                onHasNext = { hasNextCardsPage = it }
            )
            isLoadingCards = false
        }
    }

    LaunchedEffect(profileUrl) {
        if (profileUrl.isBlank()) return@LaunchedEffect
        while (true) {
            delay(CARDS_CACHE_TTL_MS)
            cardsReloadKey += 1
        }
    }

    LaunchedEffect(profileUrl, externalReloadKey, selectedTab, cardsReloadKey) {
        if (profileUrl.isBlank()) return@LaunchedEffect
        if (selectedTab != Tab.Statistics && accountCardsTotal > 0) return@LaunchedEffect
        accountStatsLoading = true
        val networkCards = loadAllProfileCards(profileUrl)
        val cachedCards = loadAllCachedCards(context, profileUrl)
        val allCards = if (networkCards.isNotEmpty()) networkCards else cachedCards
        accountCardsTotal = allCards.sumOf { it.count }
        accountRankStats = FarmService.CARD_RANKS.associateWith { rank ->
            allCards.filter { it.rank.equals(rank, ignoreCase = true) }.sumOf { it.count }
        }
        accountStatsLoading = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            val running = isFarmServiceRunning(context)
            if (isFarming != running) isFarming = running
            val message = loadFarmServiceMessage(context)
            if (message.isNotBlank() && message != lastFarmMessage) {
                lastFarmMessage = message
            }
            farmProgress = loadFarmProgress(context)
            farmStatistics = loadFarmStatistics(context)
            kotlinx.coroutines.delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            if (selectedTab != Tab.Farm) {
                CenterAlignedTopAppBar(
                    title = { Text("Senkuro Farm", fontWeight = FontWeight.Black) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ),
                    actions = {
                        IconButton(onClick = onOpenSession) {
                            Icon(Icons.Rounded.Login, contentDescription = "Открыть Senkuro")
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            FarmScreen(
                isFarming = isFarming,
                isVisible = selectedTab == Tab.Farm,
                farmMessage = lastFarmMessage,
                farmProgress = farmProgress,
                onFarmingChange = { running, mangaUrl, threads, delaySeconds ->
                    isFarming = running
                    if (running) {
                        startFarmService(context, mangaUrl, threads, delaySeconds)
                    } else {
                        stopFarmService(context)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (selectedTab == Tab.Farm) 1f else 0f)
            )
            when (selectedTab) {
                Tab.Home -> HomeScreen(
                    modifier = Modifier.zIndex(2f),
                    cards = cards,
                    isLoading = isLoadingCards,
                    profileUrl = profileUrl,
                    profileInput = profileInput,
                    cardsStatus = cardsStatus,
                    cardsPage = cardsPage,
                    hasNextCardsPage = hasNextCardsPage,
                    onProfileUrlChange = { profileInput = it },
                    onProfileUrlApply = {
                        profileUrl = profileInput
                        cardsPage = 1
                        cardsPageCursors.clear()
                        cardsPageCache.clear()
                        onProfileUrlSaved(profileInput)
                        cardsReloadKey += 1
                    },
                    onRefresh = {
                        cardsPageCache.remove(cardsPage)
                        clearCachedCardsPage(context, profileUrl, cardsPage)
                        cardsReloadKey += 1
                    },
                    onPreviousPage = {
                        if (cardsPage > 1) {
                            cardsPage -= 1
                        }
                    },
                    onNextPage = {
                        if (hasNextCardsPage) {
                            cardsPage += 1
                        }
                    }
                )
                Tab.Farm -> Unit
                Tab.Statistics -> StatisticsScreen(
                    farmStatistics = farmStatistics,
                    accountCardsTotal = accountCardsTotal,
                    accountRankStats = accountRankStats,
                    accountStatsLoading = accountStatsLoading,
                    modifier = Modifier.zIndex(2f)
                )
                Tab.Settings -> SettingsScreen(darkTheme = darkTheme, onDarkThemeChange = onDarkThemeChange, modifier = Modifier.zIndex(2f))
                Tab.Info -> InfoScreen(
                    onOpenLicense = onOpenLicense,
                    currentVersion = currentVersion,
                    checkingUpdate = checkingUpdate,
                    onCheckUpdate = onCheckUpdate,
                    modifier = Modifier.zIndex(2f)
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    cards: List<CardItem>,
    isLoading: Boolean,
    profileUrl: String,
    profileInput: String,
    cardsStatus: String,
    cardsPage: Int,
    hasNextCardsPage: Boolean,
    onProfileUrlChange: (String) -> Unit,
    onProfileUrlApply: () -> Unit,
    onRefresh: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit
) {
    var showProfileEditor by remember(profileUrl) { mutableStateOf(profileUrl.isBlank()) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Коллекция карт", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(cardsStatus)
                if (profileUrl.isNotBlank()) {
                    Text("Профиль: $profileUrl", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Обновить") }
        }
        Spacer(Modifier.height(12.dp))
        if (showProfileEditor) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = profileInput,
                    onValueChange = onProfileUrlChange,
                    label = { Text("Профиль, если авто не нашёл") },
                    placeholder = { Text("/users/@id или nick") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                FilledTonalButton(onClick = {
                    showProfileEditor = false
                    onProfileUrlApply()
                }) { Text("OK") }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            OutlinedButton(onClick = { showProfileEditor = true }) { Text("Сменить профиль") }
            Spacer(Modifier.height(12.dp))
        }
        if (isLoading) {
            CenterMessage("Загружаю карточки Senkuro...")
        } else if (cards.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("Карты не найдены. Проверьте сеть или авторизацию.", style = MaterialTheme.typography.bodyLarge)
                }
                if (cardsPage > 1) {
                    CardPager(
                        page = cardsPage,
                        canGoBack = true,
                        canGoForward = false,
                        onPreviousPage = onPreviousPage,
                        onNextPage = onNextPage
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                CardPager(
                    page = cardsPage,
                    canGoBack = cardsPage > 1,
                    canGoForward = hasNextCardsPage,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage
                )
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(cards) { card -> CardTile(card) }
                }
            }
        }
    }
}

@Composable
private fun CardPager(
    page: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onPreviousPage,
            enabled = canGoBack,
            modifier = Modifier.weight(1f)
        ) { Text("Назад") }
        Text(
            text = "Стр. $page",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black
        )
        Button(
            onClick = onNextPage,
            enabled = canGoForward,
            modifier = Modifier.weight(1f)
        ) { Text("Вперёд") }
    }
}

@Composable
private fun CardTile(card: CardItem) {
    Card(
        modifier = Modifier.height(330.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, rankColor(card.rank).copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                if (card.isShard) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                            .clip(shardShape)
                            .background(rankColor(card.rank).copy(alpha = 0.8f))
                            .padding(3.dp)
                            .clip(shardShape)
                    ) {
                        AsyncImage(
                            model = card.imageUrl,
                            contentDescription = "Осколок ${card.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.22f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.16f)
                                        )
                                    )
                                )
                        )
                    }
                    Text(
                        "ОСКОЛОК",
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = card.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.38f to Color.Transparent,
                                    0.48f to Color.White.copy(alpha = 0.08f),
                                    0.54f to Color.White.copy(alpha = 0.28f),
                                    0.61f to Color.White.copy(alpha = 0.06f),
                                    0.72f to Color.Transparent,
                                    1.0f to Color.Transparent
                                ),
                                start = Offset(0f, 650f),
                                end = Offset(900f, 0f)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.10f))
                            )
                        )
                )
                AssistChip(
                    onClick = {},
                    label = { Text(card.rank, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(
                    card.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
            }
            Text("x${card.count}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

private val shardShape = GenericShape { size, _ ->
    moveTo(size.width * 0.18f, 0f)
    lineTo(size.width * 0.83f, size.height * 0.07f)
    lineTo(size.width, size.height * 0.38f)
    lineTo(size.width * 0.78f, size.height * 0.58f)
    lineTo(size.width * 0.88f, size.height)
    lineTo(size.width * 0.28f, size.height * 0.90f)
    lineTo(0f, size.height * 0.62f)
    lineTo(size.width * 0.12f, size.height * 0.34f)
    close()
}

@Composable
private fun FarmScreen(
    isFarming: Boolean,
    isVisible: Boolean,
    farmMessage: String,
    farmProgress: FarmProgress,
    onFarmingChange: (Boolean, String, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentUrl by remember { mutableStateOf("https://senkuro.me/") }
    var farmWebView by remember { mutableStateOf<WebView?>(null) }
    var delay by remember { mutableFloatStateOf(3f) }
    var controlsExpanded by remember { mutableStateOf(false) }
    var pauseSiteRendering by remember { mutableStateOf(false) }

    LaunchedEffect(isFarming) {
        if (isFarming) {
            controlsExpanded = false
            delay(2_000)
            pauseSiteRendering = true
        } else {
            pauseSiteRendering = false
            delay(250)
            val webView = farmWebView ?: return@LaunchedEffect
            val restoredUrl = farmProgress.url.takeIf(::isReaderPageUrl)
            if (restoredUrl != null &&
                normalizeReaderLocation(webView.url.orEmpty()) != normalizeReaderLocation(restoredUrl)
            ) {
                webView.loadUrl(restoredUrl)
                delay(1_200)
            }
            if (farmProgress.max > 0) {
                webView.evaluateJavascript(syncFarmProgressScript(farmProgress), null)
            }
        }
    }

    LaunchedEffect(isVisible, pauseSiteRendering, farmWebView) {
        val webView = farmWebView ?: return@LaunchedEffect
        while (isVisible && !pauseSiteRendering) {
            webView.evaluateJavascript("location.href") { rawUrl ->
                cleanJsString(rawUrl)
                    .takeIf { it.startsWith("https://senkuro.me/") }
                    ?.let { currentUrl = it }
            }
            delay(1_000)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        FarmSiteWebView(
            isFarming = isFarming,
            isVisible = isVisible,
            delaySeconds = delay.toInt(),
            farmProgress = farmProgress,
            onUrlChanged = { currentUrl = it },
            onWebViewReady = { farmWebView = it },
            renderSite = !pauseSiteRendering,
            modifier = Modifier.fillMaxSize()
        )

        if (isVisible && isFarming) {
            FarmingActiveScreen(
                farmProgress = farmProgress,
                farmMessage = farmMessage,
                onStop = {
                    onFarmingChange(false, currentUrl, 1, delay.toInt())
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (isVisible && controlsExpanded) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { controlsExpanded = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) { Text("⌄") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = {}, label = { Text(if (isFarming) "Фарм работает" else "Фарм остановлен") })
                        Spacer(Modifier.weight(1f))
                        Text("${delay.toInt()} сек", fontWeight = FontWeight.Bold)
                    }
                    Slider(value = delay, onValueChange = { delay = it }, valueRange = 1f..15f, steps = 13)
                    Button(
                        onClick = {
                            if (isFarming) {
                                onFarmingChange(false, currentUrl, 1, delay.toInt())
                            } else {
                                farmWebView?.evaluateJavascript("location.href") { rawUrl ->
                                    val actualUrl = cleanJsString(rawUrl)
                                    currentUrl = actualUrl
                                    if (isReaderPageUrl(actualUrl)) {
                                        onFarmingChange(true, actualUrl, 1, delay.toInt())
                                    }
                                }
                            }
                        },
                        enabled = isFarming || farmWebView != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFarming) Color(0xFFE5484D) else Color(0xFF2FB344)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(if (isFarming) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isFarming) "Стоп" else "Старт")
                    }
                    Text("URL: $currentUrl", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!isFarming && !isReaderPageUrl(currentUrl)) {
                        Text(
                            "Откройте конкретную главу перед запуском.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (farmMessage.isNotBlank()) {
                        Text(farmMessage, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (isFarming) {
                        Text(
                            "Фоновая WebView: шаг ${farmProgress.ticks}, позиция ${farmProgress.position}/${farmProgress.max}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else if (isVisible) {
            Button(
                onClick = { controlsExpanded = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("⌃") }
        }
    }
}

@Composable
private fun FarmingActiveScreen(
    farmProgress: FarmProgress,
    farmMessage: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(112.dp),
                    strokeWidth = 7.dp
                )
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.FlashOn,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Фарм работает",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Сайт временно скрыт для снижения нагрузки",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Шаг ${farmProgress.ticks}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Карт за сессию: ${farmProgress.sessionCards}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Позиция ${farmProgress.position}/${farmProgress.max}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (farmMessage.isNotBlank()) {
                        Text(
                            farmMessage,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5484D))
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Остановить фарм")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "После остановки книга откроется на текущем месте",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
@SuppressLint("JavascriptInterface")
private fun FarmSiteWebView(
    isFarming: Boolean,
    isVisible: Boolean,
    delaySeconds: Int,
    farmProgress: FarmProgress,
    onUrlChanged: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    renderSite: Boolean,
    modifier: Modifier = Modifier
) {
    val appContext = LocalContext.current.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val lastVisibleSyncAt = remember { longArrayOf(0L) }
    val lastAppliedDelay = remember { intArrayOf(-1) }
    val lastFarmingState = remember { arrayOfNulls<Boolean>(1) }
    val bridge = remember(onUrlChanged, appContext) {
        FarmWebBridge(
            mainHandler = mainHandler,
            onUrlChanged = onUrlChanged,
            onCardCollected = { name, rank ->
                recordVisibleCard(appContext, name, rank)
            }
        )
    }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        factory = { context ->
            restoreSenkuroCookies(context.applicationContext)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(bridge, "SenkuroFarm")
                visibility = if (isVisible && renderSite) View.VISIBLE else View.GONE
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        view.loadUrl(request.url.toString())
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        saveSenkuroCookies(appContext)
                        onUrlChanged(url)
                        view.evaluateJavascript(farmReaderScript(false, delaySeconds), null)
                        if (isFarming) {
                            view.evaluateJavascript(existingCardCollectorScript(), null)
                        }
                    }
                }
                loadUrl(
                    farmProgress.url
                        .takeIf { it.startsWith("https://senkuro.me/") }
                        ?: "https://senkuro.me/"
                )
                post { onWebViewReady(this) }
            }
        },
        update = { webView ->
            onWebViewReady(webView)
            webView.visibility = if (isVisible && renderSite) View.VISIBLE else View.GONE
            if (isVisible && renderSite) webView.onResume() else webView.onPause()
            if (!renderSite) {
                webView.evaluateJavascript(
                    """
                        window.__senkuroVisibleCollectorDispose &&
                          window.__senkuroVisibleCollectorDispose();
                        if (window.__senkuroFarmUrlTimer) {
                          clearInterval(window.__senkuroFarmUrlTimer);
                          window.__senkuroFarmUrlTimer = null;
                        }
                    """.trimIndent(),
                    null
                )
                return@AndroidView
            }
            if (lastAppliedDelay[0] != delaySeconds) {
                lastAppliedDelay[0] = delaySeconds
                webView.evaluateJavascript(farmReaderScript(false, delaySeconds), null)
            }
            if (lastFarmingState[0] != isFarming) {
                lastFarmingState[0] = isFarming
                webView.evaluateJavascript(
                    if (isFarming) existingCardCollectorScript()
                    else "window.__senkuroVisibleCollectorDispose && window.__senkuroVisibleCollectorDispose();",
                    null
                )
            }
            val backgroundUrl = farmProgress.url.takeIf {
                it.startsWith("https://senkuro.me/") && "/chapters/" in it
            }
            val visibleUrl = webView.url.orEmpty()
            if (isFarming && isVisible && backgroundUrl != null &&
                normalizeReaderLocation(visibleUrl) != normalizeReaderLocation(backgroundUrl)
            ) {
                webView.loadUrl(backgroundUrl)
            } else if (isFarming && isVisible && farmProgress.max > 0) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastVisibleSyncAt[0] >= VISIBLE_FARM_SYNC_INTERVAL_MS) {
                    lastVisibleSyncAt[0] = now
                    webView.evaluateJavascript(syncFarmProgressScript(farmProgress), null)
                }
            }
        }
    )
}

private fun normalizeReaderLocation(url: String): String =
    url.substringBefore('?').substringBefore('#')

private fun isReaderPageUrl(url: String): Boolean =
    url.startsWith("https://senkuro.me/") &&
        Regex("/chapters/[^/]+/pages/\\d+").containsMatchIn(url)

internal fun existingCardCollectorScript(): String = """
    (function() {
      window.__senkuroVisibleCollectorDispose && window.__senkuroVisibleCollectorDispose();
      let reported = false;
      let state = 'idle';
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
      const dispatchClick = element => {
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
      const check = () => {
        const modal = findModal();
        if (modal) {
          const cardName =
            modal.querySelector('.collectible-card__front img')?.getAttribute('alt') ||
            modal.querySelector('.collectible-card img')?.getAttribute('alt') ||
            modal.querySelector('img[alt]')?.getAttribute('alt') ||
            'Карточка';
          const cardLink = modal.querySelector('a.collectible-card')?.getAttribute('href') || '';
          const rankMatch = cardLink.match(/-((?:sr)|[sabcdef])$/i);
          const cardRank =
            modal.querySelector('[data-rank]')?.getAttribute('data-rank') ||
            rankMatch?.[1]?.toUpperCase() ||
            '?';
          const x = Math.floor(window.innerWidth / 2);
          const y = Math.max(1, window.innerHeight - 12);
          dispatchClick(document.elementFromPoint(x, y) || modal);
          if (!reported && window.SenkuroFarm?.onCardCollected) {
            reported = true;
            window.SenkuroFarm.onCardCollected(cardName, cardRank);
          }
          state = 'closing';
          return;
        }
        if (state === 'closing') {
          state = 'idle';
          reported = false;
        }
        const drop = findDrop();
        if (drop && state !== 'opening') {
          state = 'opening';
          dispatchClick(drop);
        } else if (!drop && state === 'opening') {
          state = 'idle';
        }
      };
      let queued = false;
      const observer = new MutationObserver(function() {
        if (queued) return;
        queued = true;
        requestAnimationFrame(function() {
          queued = false;
          check();
        });
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
      const timer = setInterval(check, 350);
      window.__senkuroVisibleCollectorDispose = function() {
        observer.disconnect();
        clearInterval(timer);
      };
      check();
    })();
""".trimIndent()

private fun syncFarmProgressScript(progress: FarmProgress): String {
    val ratio = (progress.position.toDouble() / progress.max.coerceAtLeast(1).toDouble())
        .coerceIn(0.0, 1.0)
    return """
        (function() {
          const root = document.scrollingElement || document.documentElement;
          const max = Math.max(0, root.scrollHeight - window.innerHeight);
          window.scrollTo({
            top: Math.floor(max * $ratio),
            behavior: 'smooth'
          });
        })();
    """.trimIndent()
}

private fun farmReaderScript(isFarming: Boolean, delaySeconds: Int): String = """
    (function() {
      if (window.SenkuroFarm && window.SenkuroFarm.onUrlChanged) {
        window.SenkuroFarm.onUrlChanged(location.href);
      }
      if (window.__senkuroFarmUrlTimer) clearInterval(window.__senkuroFarmUrlTimer);
      window.__senkuroFarmUrlTimer = setInterval(function() {
        if (window.SenkuroFarm && window.SenkuroFarm.onUrlChanged) window.SenkuroFarm.onUrlChanged(location.href);
      }, 1000);
      if (window.__senkuroFarmTimer) clearInterval(window.__senkuroFarmTimer);
      window.__senkuroFarmRunId = (window.__senkuroFarmRunId || 0) + 1;
      if (!${isFarming}) return;
      const runId = window.__senkuroFarmRunId;
      window.__senkuroFarmScrolling = false;
      const scrollLikeReading = function() {
        if (window.__senkuroFarmScrolling) return;
        const max = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight) - window.innerHeight;
        const step = Math.max(180, Math.floor(window.innerHeight * 0.55));
        const start = window.scrollY;
        const target = window.scrollY >= max - 20 ? 0 : Math.min(max, start + step);
        const duration = Math.min(900, Math.max(350, ${delaySeconds.coerceAtLeast(1) * 350}));
        const startedAt = performance.now();
        window.__senkuroFarmScrolling = true;
        const animate = function(now) {
          if (runId !== window.__senkuroFarmRunId) {
            window.__senkuroFarmScrolling = false;
            return;
          }
          const progress = Math.min(1, (now - startedAt) / duration);
          const eased = 1 - Math.pow(1 - progress, 3);
          window.scrollTo(0, start + (target - start) * eased);
          if (progress < 1) requestAnimationFrame(animate);
          else window.__senkuroFarmScrolling = false;
        };
        requestAnimationFrame(animate);
      };
      window.__senkuroFarmTimer = setInterval(function() {
        scrollLikeReading();
      }, ${delaySeconds.coerceAtLeast(1) * 1000});
    }
)();
""".trimIndent()

@Composable
private fun StatisticsScreen(
    farmStatistics: FarmStatistics,
    accountCardsTotal: Int,
    accountRankStats: Map<String, Int>,
    accountStatsLoading: Boolean,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Статистика", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black) }
        item {
            SectionCard("Работа бота") {
                Text(
                    formatRuntime(farmStatistics.runtimeMs),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("Общее накопленное время фарма")
            }
        }
        item {
            SectionCard("Карты на аккаунте") {
                Text(
                    if (accountStatsLoading) "Загрузка всей коллекции…" else "Всего экземпляров: $accountCardsTotal",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                RankStatistics(accountRankStats)
            }
        }
        item {
            SectionCard("Выбито ботом приложения") {
                Text(
                    "Всего собрано: ${farmStatistics.botCards}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                RankStatistics(farmStatistics.botRanks)
            }
        }
    }
}

@Composable
private fun RankStatistics(values: Map<String, Int>) {
    FarmService.CARD_RANKS.chunked(2).forEach { ranks ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ranks.forEach { rank ->
                RankMiniCard(
                    rank = rank,
                    count = values[rank] ?: 0,
                    modifier = Modifier.weight(1f)
                )
            }
            if (ranks.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RankMiniCard(rank: String, count: Int, modifier: Modifier = Modifier) {
    val color = rankColor(rank)
    Card(
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.9f)),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.13f))
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(66.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(rank, color = Color.White, fontWeight = FontWeight.Black)
                Text(
                    "✦",
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Ранг $rank", style = MaterialTheme.typography.bodySmall)
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = color
                )
            }
        }
    }
}

private fun formatRuntime(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    val days = totalMinutes / 1_440L
    val hours = totalMinutes % 1_440L / 60L
    val minutes = totalMinutes % 60L
    return when {
        days > 0 -> "$days дн. $hours ч. $minutes мин."
        hours > 0 -> "$hours ч. $minutes мин."
        else -> "$minutes мин."
    }
}

@Composable
private fun SettingsScreen(darkTheme: Boolean, onDarkThemeChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var persistentNotification by remember { mutableStateOf(true) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black) }
        item {
            SectionCard("Внешний вид") {
                SettingRow(Icons.Rounded.DarkMode, "Тёмная тема", "Переключение оформления приложения") {
                    Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
                }
            }
        }
        item {
            SectionCard("Уведомления") {
                SettingRow(Icons.Rounded.Article, "Постоянное уведомление", "Показывать работу фарма и кнопку Стоп") {
                    Switch(checked = persistentNotification, onCheckedChange = { persistentNotification = it })
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("Выдать разрешение на уведомления") }
            }
        }
        item {
            SectionCard("Система") {
                Button(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.BatterySaver, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть настройки батареи")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.fillMaxWidth()) { Text("Настройки приложения / автозапуск") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить поверх других окон") }
            }
        }
    }
}

@Composable
private fun InfoScreen(
    onOpenLicense: () -> Unit,
    currentVersion: String,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Информация", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black) }
        item {
            SectionCard("Senkuro Farm") {
                Text("Версия: $currentVersion")
                Text("Статус: Open Source")
                Text("Лицензия: MIT")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Приложение создано для безопасного использования. Программа не является официальным продуктом Senkuro и не аффилирована с ним. Все права на торговые марки и контент Senkuro принадлежат их законным владельцам. Приложение использует открытый веб-интерфейс сайта. Пароль пользователя не сохраняется и не передается третьим лицам. Приложение создано с помощью GPT-5.5."
                )
            }
        }
        item {
            SectionCard("Обновления") {
                Text("Новые версии загружаются из официального GitHub-репозитория проекта.")
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onCheckUpdate,
                    enabled = !checkingUpdate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (checkingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (checkingUpdate) "Проверка…" else "Проверить обновления")
                }
            }
        }
        item {
            SectionCard("Разработчик") {
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Matvel007/SenkuroFarm")
                        )
                    )
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("GitHub проекта")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenLicense, modifier = Modifier.fillMaxWidth()) { Text("Лицензия") }
            }
        }
    }
}

@Composable
private fun LicenseScreen(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(onClick = onBack) { Text("Назад") }
            Spacer(Modifier.height(12.dp))
            Text("MIT License", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        }
        item {
            Text(
                "Copyright (c) 2026 Matvel007\n\n" +
                    "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the Software), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\n" +
                    "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.\n\n" +
                    "THE SOFTWARE IS PROVIDED AS IS, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        control()
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(value, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun rankColor(rank: String): Color = when (rank) {
    "SR" -> Color(0xFF34363A)
    "S" -> Color(0xFFE12D39)
    "A" -> Color(0xFFF29A0A)
    "B" -> Color(0xFFBE2ED6)
    "C" -> Color(0xFF12B9D8)
    "D" -> Color(0xFF43AE3E)
    "F" -> Color(0xFFA5A5A5)
    else -> Color(0xFF6C7080)
}

private fun showCardsPage(
    target: MutableList<CardItem>,
    result: CardsPage,
    page: Int,
    onStatus: (String) -> Unit,
    onHasNext: (Boolean) -> Unit
) {
    target.clear()
    target.addAll(result.cards)
    onStatus(
        if (result.cards.isEmpty()) {
            if (page > 1) "На странице $page карточек нет. Вернитесь назад."
            else "Карты профиля не найдены. Проверьте ссылку, авторизацию или приватность профиля."
        } else {
            "Страница $page: ${result.cards.size} карт · кеш включён"
        }
    )
    onHasNext(result.hasNextPage || result.cards.size >= CARDS_PAGE_SIZE)
}

private fun cardsCacheKey(profileUrl: String, page: Int): String =
    "cards_v3_${normalizeProfileUrl(profileUrl).hashCode()}_$page"

private fun loadCachedCardsPage(context: Context, profileUrl: String, page: Int): CachedCardsPage? {
    val raw = context.getSharedPreferences("senkuro_cards_cache", Context.MODE_PRIVATE)
        .getString(cardsCacheKey(profileUrl, page), null)
        ?: return null
    return runCatching {
        val root = JSONObject(raw)
        val items = root.getJSONArray("cards")
        val cards = buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    CardItem(
                        title = item.getString("title"),
                        rank = item.getString("rank"),
                        imageUrl = item.getString("imageUrl"),
                        count = item.optInt("count", 1),
                        isShard = item.optBoolean("isShard"),
                        modifier = item.optInt("modifier")
                    )
                )
            }
        }
        CachedCardsPage(
            page = CardsPage(
                cards = cards,
                endCursor = root.optString("endCursor").takeIf { it.isNotBlank() },
                hasNextPage = root.optBoolean("hasNextPage")
            ),
            savedAt = root.optLong("savedAt")
        )
    }.getOrNull()
}

private fun loadAllCachedCards(context: Context, profileUrl: String): List<CardItem> {
    val result = mutableListOf<CardItem>()
    for (page in 1..100) {
        val cached = loadCachedCardsPage(context, profileUrl, page) ?: break
        result += cached.page.cards
        if (!cached.page.hasNextPage) break
    }
    if (result.isNotEmpty()) return result

    // Migration fallback for APK builds that cached cards before shard support.
    val preferences = context.getSharedPreferences("senkuro_cards_cache", Context.MODE_PRIVATE)
    val legacyPrefix = "cards_${normalizeProfileUrl(profileUrl).hashCode()}_"
    preferences.all.entries
        .filter { it.key.startsWith(legacyPrefix) }
        .sortedBy { it.key.substringAfterLast('_').toIntOrNull() ?: Int.MAX_VALUE }
        .forEach { entry ->
            val raw = entry.value as? String ?: return@forEach
            runCatching {
                val items = JSONObject(raw).getJSONArray("cards")
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    result += CardItem(
                        title = item.getString("title"),
                        rank = item.getString("rank"),
                        imageUrl = item.getString("imageUrl"),
                        count = item.optInt("count", 1)
                    )
                }
            }
        }
    return result
}

private fun saveCachedCardsPage(
    context: Context,
    profileUrl: String,
    page: Int,
    cached: CachedCardsPage
) {
    val cards = org.json.JSONArray()
    cached.page.cards.forEach { card ->
        cards.put(
            JSONObject()
                .put("title", card.title)
                .put("rank", card.rank)
                .put("imageUrl", card.imageUrl)
                .put("count", card.count)
                .put("isShard", card.isShard)
                .put("modifier", card.modifier)
        )
    }
    val root = JSONObject()
        .put("savedAt", cached.savedAt)
        .put("endCursor", cached.page.endCursor.orEmpty())
        .put("hasNextPage", cached.page.hasNextPage)
        .put("cards", cards)
    context.getSharedPreferences("senkuro_cards_cache", Context.MODE_PRIVATE)
        .edit()
        .putString(cardsCacheKey(profileUrl, page), root.toString())
        .apply()
}

private fun clearCachedCardsPage(context: Context, profileUrl: String, page: Int) {
    context.getSharedPreferences("senkuro_cards_cache", Context.MODE_PRIVATE)
        .edit()
        .remove(cardsCacheKey(profileUrl, page))
        .apply()
}

private suspend fun loadProfileCards(profileUrl: String, page: Int): CardsPage = withContext(Dispatchers.IO) {
    runCatching {
        loadProfileCardsGraphQlPage(profileUrl, page)?.takeIf { it.cards.isNotEmpty() || it.hasNextPage }?.let { return@runCatching it }
        val url = normalizeProfileCardsUrl(profileUrl, page)
        val cookie = CookieManager.getInstance().getCookie("https://senkuro.me").orEmpty()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) SenkuroFarm/1.0")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        val html = networkClient.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
        val cards = parseProfileCards(html)
        CardsPage(cards = cards, endCursor = null, hasNextPage = false)
    }.getOrElse { CardsPage(cards = emptyList(), endCursor = null, hasNextPage = false) }
}

private suspend fun loadAllProfileCards(profileUrl: String): List<CardItem> = withContext(Dispatchers.IO) {
    runCatching {
        val cards = mutableListOf<CardItem>()
        var after: String? = null
        repeat(100) {
            val page = loadProfileCardsGraphQl(profileUrl, after) ?: return@runCatching cards
            cards += page.cards
            if (!page.hasNextPage || page.endCursor.isNullOrBlank()) return@runCatching cards
            after = page.endCursor
        }
        cards
    }.getOrElse { emptyList() }
}

private fun loadProfileCardsGraphQlPage(profileUrl: String, page: Int): CardsPage? {
    var after: String? = null
    var result: CardsPage? = null
    repeat(page.coerceAtLeast(1)) { index ->
        result = loadProfileCardsGraphQl(profileUrl, after) ?: return null
        if (index < page - 1) {
            after = result?.endCursor ?: return CardsPage(cards = emptyList(), endCursor = null, hasNextPage = false)
        }
    }
    return result
}

private fun loadProfileCardsGraphQl(profileUrl: String, after: String?): CardsPage? {
    val userId = resolveGraphQlUserId(profileUrl) ?: return null
    val cookie = CookieManager.getInstance().getCookie("https://senkuro.me").orEmpty()
    val query = """
        query fetchUserCards(${'$'}userId: ID!, ${'$'}after: String) {
          userCards(first: 20, after: ${'$'}after, userId: ${'$'}userId, orderBy: { field: CREATED_AT, direction: DESC }) {
            edges {
              node {
                quantity
                shard
                modifier
                card {
                  id
                  titles { lang content }
                  rank
                  image { original { url } }
                }
              }
            }
            pageInfo { hasNextPage endCursor }
          }
        }
    """.trimIndent()
    val variables = JSONObject()
        .put("userId", userId)
        .put("after", after)
    val payload = JSONObject()
        .put("query", query)
        .put("variables", variables)
        .toString()
    val request = Request.Builder()
        .url(GRAPHQL_URL)
        .header("User-Agent", "Mozilla/5.0 (Android) SenkuroFarm/1.0")
        .header("Content-Type", "application/json")
        .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    val body = networkClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        response.body?.string().orEmpty()
    }
    val root = JSONObject(body)
    val userCards = root.optJSONObject("data")?.optJSONObject("userCards") ?: return null
    val edges = userCards.optJSONArray("edges") ?: return null
    val cards = buildList {
        for (index in 0 until edges.length()) {
            val node = edges.optJSONObject(index)?.optJSONObject("node") ?: continue
            val card = node.optJSONObject("card") ?: continue
            val imageUrl = card.optJSONObject("image")
                ?.optJSONObject("original")
                ?.optString("url")
                .orEmpty()
            val rank = card.optString("rank", "?")
            val title = readLocalizedTitle(card.optJSONArray("titles"))
            if (title.isNotBlank() && imageUrl.isNotBlank() && rank != "?") {
                add(
                    CardItem(
                        title = title,
                        rank = rank,
                        imageUrl = imageUrl,
                        count = node.optInt("quantity", 1),
                        isShard = node.optBoolean("shard"),
                        modifier = node.optInt("modifier")
                    )
                )
            }
        }
    }
    val pageInfo = userCards.optJSONObject("pageInfo")
    val endCursor = pageInfo?.optString("endCursor")?.takeIf { it.isNotBlank() && it != "null" }
    val hasNextPage = pageInfo?.optBoolean("hasNextPage") == true || (cards.size >= CARDS_PAGE_SIZE && endCursor != null)
    return CardsPage(
        cards = cards,
        endCursor = endCursor,
        hasNextPage = hasNextPage
    )
}

private fun resolveGraphQlUserId(profileUrl: String): String? {
    val profileSlug = extractProfileSlug(profileUrl) ?: return null
    graphQlUserIdCache[profileSlug]?.let { return it }
    if (profileSlug.startsWith("@")) {
        val numericId = profileSlug.removePrefix("@").takeIf { it.all(Char::isDigit) } ?: return null
        return Base64.encodeToString(
            "USER:$numericId".toByteArray(),
            Base64.NO_WRAP or Base64.NO_PADDING
        ).also { graphQlUserIdCache[profileSlug] = it }
    }
    val query = "query resolveUserId(\$slug: String!) { user(slug: \$slug) { id } }"
    val payload = JSONObject()
        .put("query", query)
        .put("variables", JSONObject().put("slug", profileSlug))
        .toString()
    val request = Request.Builder()
        .url(GRAPHQL_URL)
        .header("User-Agent", "Mozilla/5.0 (Android) SenkuroFarm/1.0")
        .header("Content-Type", "application/json")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return networkClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        JSONObject(response.body?.string().orEmpty())
            .optJSONObject("data")
            ?.optJSONObject("user")
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
    }?.also { graphQlUserIdCache[profileSlug] = it }
}

internal fun extractProfileSlug(profileUrl: String): String? =
    Regex("/users/(@\\d+|[A-Za-z0-9_.-]+)")
        .find(normalizeProfileUrl(profileUrl))
        ?.groupValues
        ?.getOrNull(1)

private fun readLocalizedTitle(titles: org.json.JSONArray?): String {
    if (titles == null) return ""
    var fallback = ""
    for (index in 0 until titles.length()) {
        val item = titles.optJSONObject(index) ?: continue
        val content = item.optString("content").trim()
        if (fallback.isBlank()) fallback = content
        if (item.optString("lang") == "RU" && content.isNotBlank()) return content
    }
    return fallback
}

private fun normalizeProfileCardsUrl(raw: String, page: Int = 1): String {
    val trimmed = raw.trim()
    val absoluteRaw = when {
        trimmed.startsWith("https://senkuro.me/") -> trimmed
        trimmed.startsWith("/users/") -> "https://senkuro.me$trimmed"
        trimmed.startsWith("users/") -> "https://senkuro.me/$trimmed"
        trimmed.startsWith("@") -> "https://senkuro.me/users/$trimmed"
        trimmed.isNotBlank() -> "https://senkuro.me/users/$trimmed"
        else -> "https://senkuro.me/users/"
    }.substringBefore("?").trimEnd('/')
    val profileBase = Regex("https://senkuro\\.me/users/(?:@\\d+|[A-Za-z0-9_.-]+)")
        .find(absoluteRaw)
        ?.value
        ?: absoluteRaw
    val cardsUrl = if (profileBase.endsWith("/cards")) profileBase else "$profileBase/cards"
    return if (page <= 1) cardsUrl else "$cardsUrl?page=$page"
}

private fun normalizeProfileUrl(raw: String): String =
    if (raw.isBlank()) "" else normalizeProfileCardsUrl(raw).removeSuffix("/cards")

private fun parseProfileCards(html: String): List<CardItem> {
    val sectionStart = html.indexOf("Коллекционные карточки").takeIf { it >= 0 }
        ?: html.indexOf("Каталог карточек").takeIf { it >= 0 }
        ?: 0
    val sectionEnd = listOf(
        html.indexOf("Достижения", sectionStart),
        html.indexOf("Библиотека манги", sectionStart),
        html.indexOf("История действий", sectionStart),
        html.indexOf("Главная", sectionStart)
    ).filter { it > sectionStart }.minOrNull() ?: html.length
    val section = html.substring(sectionStart, sectionEnd)
    val imageTagRegex = Regex("<img[^>]+>")
    val altRegex = Regex("alt=\"([^\"]*)\"")
    val srcRegex = Regex("src=\"([^\"]+)\"")
    return imageTagRegex.findAll(section)
        .mapNotNull { match ->
            val tag = match.value
            val title = htmlDecode(altRegex.find(tag)?.groupValues?.getOrNull(1).orEmpty()).trim()
            val imageUrl = normalizeSenkuroImageUrl(srcRegex.find(tag)?.groupValues?.getOrNull(1).orEmpty().trim())
            val rank = detectRank(section, match.range.first)
            val isRealCard = !title.startsWith("Рамка") &&
                !title.startsWith("Рубашка") &&
                !title.contains("banner", ignoreCase = true) &&
                title.isNotBlank() &&
                title.length <= 80 &&
                imageUrl.contains("senkuro") &&
                !imageUrl.contains("card-back") &&
                !imageUrl.contains("/assets/") &&
                rank != "?"
            if (!isRealCard) null else {
                CardItem(title = title, rank = rank, imageUrl = imageUrl, count = 1)
            }
        }
        .distinctBy { it.title to it.rank }
        .take(200)
        .toList()
}

private fun normalizeSenkuroImageUrl(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> "https://senkuro.me$url"
    else -> url
}

private fun htmlDecode(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")

private fun profileDiscoveryScript(): String = """
    (function() {
      const normalize = function(value) {
        if (!value) return '';
        const url = new URL(value, location.origin).toString().split('?')[0].replace(/\/$/, '');
        return url.includes('/users/') ? url : '';
      };
      const current = normalize(location.href);
      if (current) return current;

      const storageValues = [];
      for (const storage of [localStorage, sessionStorage]) {
        for (let i = 0; i < storage.length; i++) {
          const key = storage.key(i);
          storageValues.push(String(key || '') + ' ' + String(storage.getItem(key) || ''));
        }
      }
      const storageText = storageValues.join('\n');
      const storageMatches = [...storageText.matchAll(/\/users\/(?:@\d+|[a-zA-Z0-9_.-]+)/g)].map(m => normalize(m[0]));
      const scored = storageMatches.map(url => {
        const index = storageText.indexOf(url.replace(location.origin, ''));
        const context = storageText.slice(Math.max(0, index - 160), index + 160).toLowerCase();
        const score = (context.includes('viewer') ? 5 : 0) + (context.includes('current') ? 4 : 0) + (context.includes('me') ? 3 : 0) + (context.includes('profile') ? 2 : 0);
        return { url, score };
      }).sort((a, b) => b.score - a.score);
      if (scored.length && scored[0].score > 0) return scored[0].url;

      const links = [...document.querySelectorAll('a[href*="/users/"]')]
        .map(a => ({ url: normalize(a.getAttribute('href')), text: (a.innerText || a.title || a.getAttribute('aria-label') || '').toLowerCase() }))
        .filter(x => x.url && !x.url.includes('/friends') && !x.url.includes('/follows') && !x.url.includes('/subscribers'));
      const own = links.find(x => x.text.includes('мой') || x.text.includes('профиль') || x.text.includes('карточки'));
      return (own || links[0] || {}).url || '';
    })()
""".trimIndent()

private fun cleanJsString(raw: String?): String {
    val value = raw.orEmpty().trim()
    if (value == "null" || value == "undefined") return ""
    return value.trim('"')
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\u002F", "/")
}

private suspend fun discoverProfileUrl(): String = withContext(Dispatchers.IO) {
    runCatching {
        val cookie = CookieManager.getInstance().getCookie("https://senkuro.me").orEmpty()
        if (cookie.isBlank()) return@withContext ""
        val client = OkHttpClient.Builder().followRedirects(true).build()
        val request = Request.Builder()
            .url("https://senkuro.me/")
            .header("User-Agent", "Mozilla/5.0 (Android) SenkuroFarm/1.0")
            .header("Cookie", cookie)
            .build()
        val html = client.newCall(request).execute().body?.string().orEmpty()
        Regex("href=\"(/users/(?:@\\d+|[A-Za-z0-9_.-]+))\"")
            .findAll(html)
            .map { "https://senkuro.me${it.groupValues[1]}" }
            .firstOrNull { !it.contains("/friends") && !it.contains("/follows") }
            .orEmpty()
    }.getOrElse { "" }
}

private fun detectRank(html: String, start: Int): String {
    val chunk = html.substring(start, minOf(start + 1200, html.length))
    return Regex("Рамка (SR|S|A|B|C|D|F) ранга").find(chunk)?.groupValues?.getOrNull(1) ?: "?"
}
