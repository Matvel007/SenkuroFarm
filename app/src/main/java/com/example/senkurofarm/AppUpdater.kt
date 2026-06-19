package com.example.senkurofarm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.TargetApi
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class GithubRelease(
    val version: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String
)

internal object AppUpdater {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Matvel007/SenkuroFarm/releases/latest"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private val client = OkHttpClient()

    suspend fun findUpdate(context: Context): GithubRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "SenkuroFarm-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub вернул код ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name").trim()
            val version = tag.removePrefix("v").removePrefix("V")
            val assets = json.optJSONArray("assets") ?: error("В релизе нет файлов")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                .maxByOrNull {
                    if (it.optString("name").equals("SenkuroFarm.apk", ignoreCase = true)) 1 else 0
                }
                ?: error("В релизе не найден APK")
            if (compareVersions(version, currentVersion(context)) <= 0) return@withContext null
            GithubRelease(
                version = version,
                title = json.optString("name").ifBlank { "Senkuro Farm $version" },
                notes = json.optString("body").ifBlank { "Описание обновления отсутствует." },
                apkUrl = apk.getString("browser_download_url"),
                pageUrl = json.optString("html_url")
            )
        }
    }

    suspend fun downloadAndVerify(
        context: Context,
        release: GithubRelease,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): File =
        withContext(Dispatchers.IO) {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(updatesDir, "SenkuroFarm-${release.version}.apk")
            val temporary = File(updatesDir, "${target.name}.part")
            val request = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", "SenkuroFarm-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Не удалось скачать APK: ${response.code}")
                val body = response.body ?: error("GitHub вернул пустой файл")
                val total = body.contentLength()
                temporary.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastProgressUpdate = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= 150 || downloaded == total) {
                                withContext(Dispatchers.Main) {
                                    onProgress(downloaded, total)
                                }
                                lastProgressUpdate = now
                            }
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            if (temporary.length() < 1_000_000L) {
                temporary.delete()
                error("Загруженный APK повреждён")
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            verifyApk(context, target)
            target
        }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    @TargetApi(Build.VERSION_CODES.O)
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun currentVersion(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName.orEmpty().ifBlank { "0.0.0" }
    }

    internal fun compareVersions(first: String, second: String): Int {
        val left = versionParts(first)
        val right = versionParts(second)
        repeat(maxOf(left.size, right.size)) { index ->
            val difference = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> =
        Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, apk: File) {
        val packageManager = context.packageManager
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: error("Android не распознал загруженный APK")
        if (archive.packageName != context.packageName) {
            error("APK принадлежит другому приложению")
        }
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val archiveCertificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }
        } else {
            archive.signatures.orEmpty().map { sha256(it.toByteArray()) }
        }
        val installedCertificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }
        } else {
            installed.signatures.orEmpty().map { sha256(it.toByteArray()) }
        }
        if (archiveCertificates.isEmpty() || archiveCertificates != installedCertificates) {
            error("Подпись обновления не совпадает с установленным приложением")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
