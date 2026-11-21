package com.example.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class GitHubReleaseInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val htmlUrl: String
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object UpToDate : AppUpdateState
    data class UpdateAvailable(val release: GitHubReleaseInfo) : AppUpdateState
    data class Downloading(
        val release: GitHubReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressFraction: Float
    ) : AppUpdateState

    data class ReadyToInstall(
        val release: GitHubReleaseInfo,
        val apkFile: File
    ) : AppUpdateState

    data class Error(
        val release: GitHubReleaseInfo,
        val message: String
    ) : AppUpdateState
}

/**
 * Checks GitHub Releases (`malikfaizangamerr-coder/StreamtubeRiz-mobile-wali-`) for newer APK builds,
 * triggers an update notification whenever a new version is available (silent when up-to-date),
 * streams APK downloads with full progress tracking, and launches the Android package installer.
 */
class AppUpdateManager(
    context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs = appContext.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    private val _isBannerDismissed = MutableStateFlow(false)
    val isBannerDismissed: StateFlow<Boolean> = _isBannerDismissed.asStateFlow()

    private var downloadJob: Job? = null
    @Volatile
    private var lastCheckAtMs: Long = 0L

    init {
        ensureNotificationChannel()
    }

    fun checkForUpdatesIfStale(minIntervalMs: Long = AUTO_SCAN_INTERVAL_MS) {
        val now = System.currentTimeMillis()
        if (lastCheckAtMs == 0L || (now - lastCheckAtMs) >= minIntervalMs) {
            checkForUpdates(notifyUserIfAvailable = true)
        }
    }

    fun checkForUpdates(notifyUserIfAvailable: Boolean = true) {
        scope.launch {
            try {
                lastCheckAtMs = System.currentTimeMillis()
                val release = fetchLatestGitHubRelease()
                if (release == null) {
                    _isBannerDismissed.value = false
                    _updateState.value = AppUpdateState.UpToDate
                    return@launch
                }

                val currentVersion = BuildConfig.VERSION_NAME
                val dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, null)
                val decision = evaluateReleaseForBanner(
                    remoteVersion = release.versionName,
                    currentVersion = currentVersion,
                    dismissedVersion = dismissedVersion
                )

                if (decision.isUpdateAvailable) {
                    if (!decision.shouldShowBanner) {
                        _isBannerDismissed.value = true
                    } else {
                        if (!dismissedVersion.isNullOrBlank()) {
                            prefs.edit().remove(KEY_DISMISSED_VERSION).apply()
                        }
                        _isBannerDismissed.value = false
                    }

                    // Check if same version APK was already downloaded and ready
                    val cachedApk = File(getUpdatesDir(), release.apkFileName)
                    if (cachedApk.exists() && cachedApk.length() > 1_000_000L &&
                        (release.apkSizeBytes <= 0L || cachedApk.length() == release.apkSizeBytes)
                    ) {
                        _updateState.value = AppUpdateState.ReadyToInstall(release, cachedApk)
                    } else {
                        _updateState.value = AppUpdateState.UpdateAvailable(release)
                    }

                    if (notifyUserIfAvailable && decision.shouldShowBanner) {
                        postUpdateNotification(release)
                    }
                } else {
                    clearStaleUpdateArtifacts()
                    prefs.edit().remove(KEY_DISMISSED_VERSION).apply()
                    NotificationManagerCompat.from(appContext).cancel(UPDATE_NOTIFICATION_ID)
                    _isBannerDismissed.value = false
                    _updateState.value = AppUpdateState.UpToDate
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check skipped: ${e.message}")
            }
        }
    }

    fun dismissBanner() {
        val releaseVersion = when (val state = _updateState.value) {
            is AppUpdateState.UpdateAvailable -> state.release.versionName
            is AppUpdateState.Downloading -> state.release.versionName
            is AppUpdateState.ReadyToInstall -> state.release.versionName
            is AppUpdateState.Error -> state.release.versionName
            else -> null
        }
        if (!releaseVersion.isNullOrBlank()) {
            prefs.edit().putString(KEY_DISMISSED_VERSION, releaseVersion).apply()
        }
        _isBannerDismissed.value = true
        NotificationManagerCompat.from(appContext).cancel(UPDATE_NOTIFICATION_ID)
    }

    fun postPendingNotificationIfAvailable() {
        if (_isBannerDismissed.value) return
        val release = when (val state = _updateState.value) {
            is AppUpdateState.UpdateAvailable -> state.release
            is AppUpdateState.ReadyToInstall -> state.release
            else -> null
        } ?: return
        postUpdateNotification(release)
    }

    fun startUpdateDownload(release: GitHubReleaseInfo) {
        if (release.apkDownloadUrl.isBlank()) {
            // Open GitHub release page in browser if no direct APK asset was attached yet
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { appContext.startActivity(browserIntent) }
            return
        }

        if (_updateState.value is AppUpdateState.Downloading) return
        downloadJob?.cancel()

        downloadJob = scope.launch {
            val updatesDir = getUpdatesDir()
            updatesDir.listFiles()?.forEach { old ->
                if (old.name != release.apkFileName) old.delete()
            }
            val partFile = File(updatesDir, "${release.apkFileName}.part")
            val finalFile = File(updatesDir, release.apkFileName)

            try {
                _updateState.value = AppUpdateState.Downloading(
                    release = release,
                    downloadedBytes = 0L,
                    totalBytes = release.apkSizeBytes,
                    progressFraction = 0f
                )

                val req = Request.Builder()
                    .url(release.apkDownloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "StreamTubeRiz-Updater/${BuildConfig.VERSION_NAME}")
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("GitHub download HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty APK response")
                    val total = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                    var downloaded = 0L
                    var lastEmit = 0L
                    val buf = ByteArray(64 * 1024)

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            while (true) {
                                val read = input.read(buf)
                                if (read == -1) break
                                output.write(buf, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 120L) {
                                    lastEmit = now
                                    val fraction = if (total > 0L) {
                                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    _updateState.value = AppUpdateState.Downloading(
                                        release = release,
                                        downloadedBytes = downloaded,
                                        totalBytes = total.coerceAtLeast(downloaded),
                                        progressFraction = fraction
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                partFile.renameTo(finalFile)

                _isBannerDismissed.value = false
                _updateState.value = AppUpdateState.ReadyToInstall(release, finalFile)
                withContext(Dispatchers.Main) {
                    launchApkInstaller(finalFile)
                }
            } catch (e: Exception) {
                partFile.delete()
                Log.e(TAG, "Update download failed: ${e.message}", e)
                _updateState.value = AppUpdateState.Error(
                    release = release,
                    message = e.message ?: "Update download failed. Tap Retry."
                )
            }
        }
    }

    fun installDownloadedApk(apkFile: File) {
        launchApkInstaller(apkFile)
    }

    private fun launchApkInstaller(apkFile: File) {
        if (!apkFile.exists()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !appContext.packageManager.canRequestPackageInstalls()
            ) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(permIntent)
                return
            }

            val apkUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer: ${e.message}", e)
        }
    }

    private fun getUpdatesDir(): File {
        return File(appContext.cacheDir, "updates").apply { if (!exists()) mkdirs() }
    }

    private fun clearStaleUpdateArtifacts() {
        runCatching {
            getUpdatesDir().listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }

    private fun fetchLatestGitHubRelease(): GitHubReleaseInfo? {
        val latestReq = Request.Builder()
            .url(GITHUB_LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "StreamTubeRiz/${BuildConfig.VERSION_NAME}")
            .build()

        runCatching {
            okHttpClient.newCall(latestReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val obj = JSONObject(body)
                        if (!obj.optBoolean("draft", false)) {
                            parseReleaseJson(obj)?.let { return it }
                        }
                    }
                }
            }
        }

        val listReq = Request.Builder()
            .url(GITHUB_RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "StreamTubeRiz/${BuildConfig.VERSION_NAME}")
            .build()

        runCatching {
            okHttpClient.newCall(listReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    var best: GitHubReleaseInfo? = null
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.optBoolean("draft", false)) continue
                        val parsed = parseReleaseJson(obj) ?: continue
                        if (best == null || isNewerVersion(parsed.versionName, best.versionName)) {
                            best = parsed
                        }
                    }
                    if (best != null) return best
                }
            }
        }
        return null
    }

    private fun parseReleaseJson(obj: JSONObject): GitHubReleaseInfo? {
        val tagName = obj.optString("tag_name").trim()
        val rawTitle = obj.optString("name").trim()
        if (tagName.isBlank() && rawTitle.isBlank()) return null
        val versionName = extractCleanVersion(tagName)
            .ifBlank { extractCleanVersion(rawTitle) }
        if (versionName.isBlank()) return null
        val title = rawTitle.ifBlank { "StreamTubeᴿⁱᶻ $tagName" }
        val notes = obj.optString("body").trim()
        val htmlUrl = obj.optString("html_url").ifBlank { RELEASES_PAGE_URL }

        var apkUrl = ""
        var apkName = "StreamTubeRiz-$versionName.apk"
        var apkSize = 0L

        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkUrl = url
                    apkName = name
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }

        return GitHubReleaseInfo(
            versionName = versionName,
            tagName = tagName,
            title = title,
            releaseNotes = notes,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            apkSizeBytes = apkSize,
            htmlUrl = htmlUrl
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when a new StreamTubeᴿⁱᶻ update is available"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postUpdateNotification(release: GitHubReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            2026,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("New Update Available: v${release.versionName}")
            .setContentText("Update StreamTubeᴿⁱᶻ for the best smooth experience.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("StreamTubeᴿⁱᶻ v${release.versionName} is out! Tap to download and install the update.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(UPDATE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val UPDATE_PREFS_NAME = "streamtube_update_prefs"
        private const val KEY_DISMISSED_VERSION = "dismissed_update_version"
        private const val AUTO_SCAN_INTERVAL_MS = 10 * 60 * 1000L
        private const val UPDATE_CHANNEL_ID = "streamtube_updates"
        private const val UPDATE_NOTIFICATION_ID = 9001
        private const val GITHUB_LATEST_API =
            "https://api.github.com/repos/malikfaizangamerr-coder/StreamtubeRiz-mobile-wali-/releases/latest"
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/malikfaizangamerr-coder/StreamtubeRiz-mobile-wali-/releases?per_page=10"
        const val RELEASES_PAGE_URL =
            "https://github.com/malikfaizangamerr-coder/StreamtubeRiz-mobile-wali-/releases"

        data class UpdateBannerDecision(
            val isUpdateAvailable: Boolean,
            val shouldShowBanner: Boolean
        )

        fun evaluateReleaseForBanner(
            remoteVersion: String,
            currentVersion: String,
            dismissedVersion: String?
        ): UpdateBannerDecision {
            val isUpdateAvailable = isNewerVersion(remoteVersion = remoteVersion, currentVersion = currentVersion)
            if (!isUpdateAvailable) {
                return UpdateBannerDecision(isUpdateAvailable = false, shouldShowBanner = false)
            }
            val shouldShow = dismissedVersion.isNullOrBlank() ||
                isNewerVersion(remoteVersion = remoteVersion, currentVersion = dismissedVersion)
            return UpdateBannerDecision(isUpdateAvailable = true, shouldShowBanner = shouldShow)
        }

        fun extractCleanVersion(raw: String): String {
            val match = Regex("\\d+(?:\\.\\d+)*").find(raw) ?: return ""
            return match.value
        }

        fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
            val rParts = parseVersionParts(remoteVersion)
            val cParts = parseVersionParts(currentVersion)
            if (rParts.isEmpty()) return false
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        private fun parseVersionParts(version: String): List<Int> {
            return Regex("\\d+")
                .findAll(version)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()
        }
    }
}
