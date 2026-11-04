package com.joyos.launcher.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Native bridge interface between JoyOS WebView UI and Android automotive subsystem.
 * Accessible from JavaScript as `window.AndroidBridge`.
 */
class AndroidBridge(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val packageManager: PackageManager = context.packageManager

    /**
     * Queries all launchable automotive and system applications installed on the head unit.
     * Returns a JSON array formatted for JoyOS App Drawer (`::`).
     */
    @JavascriptInterface
    fun getInstalledApps(): String {
        return try {
            val appList = JSONArray()
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }

            for (resolveInfo in activities) {
                val pkg = resolveInfo.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue // Skip JoyOS itself

                val label = try {
                    resolveInfo.loadLabel(packageManager).toString()
                } catch (_: Throwable) {
                    pkg
                }

                val appObj = JSONObject().apply {
                    put("id", pkg)
                    put("name", label)
                    put("package", pkg)
                    put("category", categorizeApp(pkg))
                }
                appList.put(appObj)
            }

            appList.toString()
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Safe fallback for installed apps: ${e.message}")
            "[]"
        }
    }

    /**
     * Launches an external application by its package name (e.g. ZLink, Waze, Spotify, Torque).
     */
    @JavascriptInterface
    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                Toast.makeText(context, "App not found: $packageName", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Throwable) {
            Toast.makeText(context, "Failed to launch: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Sets master multimedia stream volume (0 to 30 scale).
     */
    @JavascriptInterface
    fun setMasterVolume(vol: Int) {
        try {
            audioManager?.let { am ->
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val mapped = (vol.coerceIn(0, 30) * max) / 30
                am.setStreamVolume(AudioManager.STREAM_MUSIC, mapped, 0)
            }
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Volume set handled safely: ${e.message}")
        }
    }

    /**
     * Gets master multimedia stream volume scaled to 0-30.
     */
    @JavascriptInterface
    fun getMasterVolume(): Int {
        return try {
            audioManager?.let { am ->
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max > 0) (cur * 30) / max else 15
            } ?: 15
        } catch (e: Throwable) {
            15
        }
    }

    /**
     * Triggers a fast Hot-Update: writes new JoyOS UI HTML to local app storage
     * so UI updates can happen instantly from a USB drive without reinstalling APK.
     */
    @JavascriptInterface
    fun saveHotUpdate(htmlContent: String): Boolean {
        return try {
            val updateFile = File(context.filesDir, "joy_os_live.html")
            updateFile.writeText(htmlContent, Charsets.UTF_8)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Installs an APK from local storage or USB drive.
     */
    @JavascriptInterface
    fun installApk(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun categorizeApp(pkg: String): String {
        val lower = pkg.lowercase()
        return when {
            lower.contains("map") || lower.contains("waze") || lower.contains("navigation") -> "nav"
            lower.contains("music") || lower.contains("spotify") || lower.contains("radio") || lower.contains("audio") -> "media"
            lower.contains("zlink") || lower.contains("auto") || lower.contains("carplay") -> "link"
            lower.contains("torque") || lower.contains("obd") || lower.contains("tpms") -> "vehicle"
            else -> "tools"
        }
    }
}
