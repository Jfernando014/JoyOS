package com.joyos.launcher.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Metadata representing a GitHub Release for JoyOS OTA updates.
 */
data class OtaReleaseInfo(
    val version: String,
    val tagName: String,
    val changelog: String,
    val apkUrl: String?,
    val liveHtmlUrl: String?,
    val isNewer: Boolean
)

/**
 * Over-The-Air (OTA) Update Manager for JoyOS.
 * Interacts with GitHub Releases API, handles UI hot-updates, and streams APK packages.
 */
class OtaUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "OtaUpdateManager"
        private const val GITHUB_API_URL = "https://api.github.com/repos/Jfernando014/JoyOS/releases/latest"
        private const val USER_AGENT = "JoyOS-Automotive-Launcher/1.1.0"
        private const val TIMEOUT_MS = 15000
    }

    /**
     * Checks GitHub for the latest release and compares it with the current local version.
     */
    suspend fun checkLatestRelease(currentVersion: String): Result<OtaReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("GitHub API HTTP $responseCode"))
            }

            val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)

            val tagName = root.optString("tag_name", "").trim()
            val cleanRemoteVersion = tagName.removePrefix("v").trim()
            val changelog = root.optString("body", "Mejoras de rendimiento y estabilidad.")

            var apkDownloadUrl: String? = null
            var liveHtmlDownloadUrl: String? = null

            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")

                    if (name.equals("JoyOSLauncher.apk", ignoreCase = true) || name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = downloadUrl
                    } else if (name.equals("joy_os_live.html", ignoreCase = true)) {
                        liveHtmlDownloadUrl = downloadUrl
                    }
                }
            }

            val isNewer = isRemoteVersionNewer(currentVersion, cleanRemoteVersion)

            val info = OtaReleaseInfo(
                version = cleanRemoteVersion,
                tagName = tagName,
                changelog = changelog,
                apkUrl = apkDownloadUrl,
                liveHtmlUrl = liveHtmlDownloadUrl,
                isNewer = isNewer
            )

            Log.i(TAG, "OTA Check complete: local=$currentVersion, remote=$cleanRemoteVersion, isNewer=$isNewer")
            Result.success(info)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check OTA releases: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a Hot-Update UI package directly into internal app storage (filesDir/joy_os_live.html).
     * Reloads WebView without restarting the Android process.
     */
    suspend fun downloadHotUpdate(
        urlStr: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }

            val totalBytes = connection.contentLength
            val tempFile = File(context.filesDir, "joy_os_live.tmp")
            val targetFile = File(context.filesDir, "joy_os_live.html")

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalDownloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        if (totalBytes > 0) {
                            val progress = ((totalDownloaded * 100) / totalBytes).toInt()
                            onProgress(progress.coerceIn(0, 100))
                        }
                    }
                    output.flush()
                }
            }

            // Sanity verification: Ensure downloaded file is a non-empty HTML document
            if (!tempFile.exists() || tempFile.length() < 1024) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Downloaded hot-update file is corrupted or too small"))
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "Hot-update successfully written to: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            onProgress(100)
            Result.success(targetFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Hot-update download failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads the full JoyOSLauncher.apk and returns the file ready for system installation.
     */
    suspend fun downloadApkUpdate(
        urlStr: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }

            val totalBytes = connection.contentLength
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val apkFile = File(downloadDir, "JoyOS_Update.apk")

            if (apkFile.exists()) {
                apkFile.delete()
            }

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalDownloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        if (totalBytes > 0) {
                            val progress = ((totalDownloaded * 100) / totalBytes).toInt()
                            onProgress(progress.coerceIn(0, 100))
                        }
                    }
                    output.flush()
                }
            }

            if (!apkFile.exists() || apkFile.length() < 100_000) {
                apkFile.delete()
                return@withContext Result.failure(Exception("Downloaded APK file is incomplete"))
            }

            Log.i(TAG, "APK successfully downloaded to: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            onProgress(100)
            Result.success(apkFile)
        } catch (e: Throwable) {
            Log.e(TAG, "APK download failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Triggers the Android PackageInstaller with FileProvider content URI.
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }

    /**
     * Semantic version comparator. Returns true if remote is strictly newer than local.
     */
    private fun isRemoteVersionNewer(local: String, remote: String): Boolean {
        try {
            val lParts = local.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val rParts = remote.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(lParts.size, rParts.size)
            for (i in 0 until maxLen) {
                val lVal = lParts.getOrElse(i) { 0 }
                val rVal = rParts.getOrElse(i) { 0 }
                if (rVal > lVal) return true
                if (rVal < lVal) return false
            }
            return false
        } catch (_: Throwable) {
            return remote != local
        }
    }
}
