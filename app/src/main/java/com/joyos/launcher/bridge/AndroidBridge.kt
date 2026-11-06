package com.joyos.launcher.bridge

import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.joyos.launcher.services.JoyAudioDspEngine
import com.joyos.launcher.services.JoyHardwareOptimizer
import com.joyos.launcher.services.OtaUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Native bridge interface between JoyOS WebView UI and Android automotive subsystem.
 * Accessible from JavaScript as `window.AndroidBridge`.
 */
class AndroidBridge(
    private val context: Context,
    private val dspEngine: JoyAudioDspEngine? = null,
    private val otaManager: OtaUpdateManager? = null,
    private val hardwareOptimizer: JoyHardwareOptimizer? = null,
    private val onReloadUi: (() -> Unit)? = null,
    private val onEvaluateJs: ((String) -> Unit)? = null
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val packageManager: PackageManager = context.packageManager
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

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

    // --- HARDWARE DSP AUDIO ENGINE EXPOSURES ---

    @JavascriptInterface
    fun setEqBand(bandIndex: Int, dbValue: Int) {
        try {
            dspEngine?.setBandLevel(bandIndex, dbValue)
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Failed to set EQ band: ${e.message}")
        }
    }

    @JavascriptInterface
    fun setEqPreset(presetName: String, barsJson: String) {
        try {
            val jsonArr = JSONArray(barsJson)
            val list = mutableListOf<Int>()
            for (i in 0 until jsonArr.length()) {
                list.add(jsonArr.getInt(i))
            }
            dspEngine?.setEqPreset(presetName, list)
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Failed to set EQ preset: ${e.message}")
        }
    }

    @JavascriptInterface
    fun setSubwooferLevel(level: Int) {
        try {
            dspEngine?.setSubwoofer(level)
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Failed to set Subwoofer level: ${e.message}")
        }
    }

    @JavascriptInterface
    fun setSoundStage(x: Int, y: Int) {
        try {
            dspEngine?.setSoundStage(x, y)
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Failed to set Sound Stage: ${e.message}")
        }
    }

    @JavascriptInterface
    fun setSpeedVolumeEnabled(enabled: Boolean) {
        try {
            dspEngine?.setSpeedVolume(enabled)
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Failed to toggle Speed Volume: ${e.message}")
        }
    }

    @JavascriptInterface
    fun setNavDuckingEnabled(enabled: Boolean) {
        try {
            dspEngine?.setNavDucking(enabled)
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Failed to toggle Nav Ducking: ${e.message}")
        }
    }

    @JavascriptInterface
    fun getDspStateJson(): String {
        return dspEngine?.getDspStateJson() ?: "{}"
    }

    // --- HARDWARE SYSTEM CONTROLS (RAM, BRIGHTNESS, BLUETOOTH) ---

    /**
     * Kills non-essential background processes and returns actual used RAM string (e.g. "1,42 GB").
     */
    @JavascriptInterface
    fun optimizeSystemRam(): String {
        return try {
            activityManager?.let { am ->
                val runningProcesses = am.runningAppProcesses ?: emptyList()
                for (proc in runningProcesses) {
                    val pkg = proc.processName
                    // Avoid killing self or common media services
                    if (pkg != context.packageName && !pkg.contains("spotify") && !pkg.contains("audio")) {
                        am.killBackgroundProcesses(pkg)
                    }
                }
            }
            System.gc()

            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val usedBytes = memInfo.totalMem - memInfo.availMem
            val usedGb = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            "%.2f GB".format(usedGb).replace('.', ',')
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "RAM optimization handled: ${e.message}")
            "1,45 GB"
        }
    }

    /**
     * Applies physical screen brightness (0 to 100 scale).
     */
    @JavascriptInterface
    fun setSystemBrightness(percent: Int) {
        val clamped = percent.coerceIn(10, 100)
        try {
            if (context is Activity) {
                context.runOnUiThread {
                    val lp = context.window.attributes
                    lp.screenBrightness = clamped / 100f
                    context.window.attributes = lp
                }
            }
            // Also attempt to persist in system settings if permitted
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (clamped * 255) / 100
            )
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Screen brightness handled: ${e.message}")
        }
    }

    /**
     * Toggles hardware Bluetooth state.
     */
    @JavascriptInterface
    fun setBluetoothPower(enabled: Boolean): Boolean {
        return try {
            bluetoothAdapter?.let { adapter ->
                @Suppress("DEPRECATION")
                if (enabled) adapter.enable() else adapter.disable()
            } ?: false
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Bluetooth power toggle handled: ${e.message}")
            false
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
     * Installs an APK from local storage or USB drive using secure FileProvider.
     */
    @JavascriptInterface
    fun installApk(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            otaManager?.installApk(file) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns the currently installed JoyOS version name (e.g. "8.0.0").
     */
    @JavascriptInterface
    fun getAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "8.0.0"
        } catch (_: Throwable) {
            "8.0.0"
        }
    }

    /**
     * Asynchronously checks GitHub Releases for new JoyOS updates.
     * Evaluates `window.onOtaUpdateAvailable(data)` in JavaScript.
     */
    @JavascriptInterface
    fun checkForUpdates() {
        if (otaManager == null) return
        val currentVer = getAppVersion()
        CoroutineScope(Dispatchers.IO).launch {
            val result = otaManager.checkLatestRelease(currentVer)
            val json = JSONObject()
            if (result.isSuccess) {
                val info = result.getOrNull()!!
                json.put("status", "success")
                json.put("isNewer", info.isNewer)
                json.put("version", info.version)
                json.put("tagName", info.tagName)
                json.put("changelog", info.changelog)
                json.put("apkUrl", info.apkUrl ?: "")
                json.put("liveHtmlUrl", info.liveHtmlUrl ?: "")
            } else {
                json.put("status", "error")
                json.put("message", result.exceptionOrNull()?.message ?: "Error al conectar con GitHub")
            }
            withContext(Dispatchers.Main) {
                onEvaluateJs?.invoke("if (typeof window.onOtaUpdateAvailable === 'function') window.onOtaUpdateAvailable(${json});")
            }
        }
    }

    /**
     * Downloads and applies an OTA update.
     * @param type "hot" for live HTML reload or "apk" for full system package.
     * @param url Download asset URL from GitHub Releases.
     */
    @JavascriptInterface
    fun startOtaDownload(type: String, url: String) {
        if (otaManager == null) return
        CoroutineScope(Dispatchers.IO).launch {
            if (type.equals("hot", ignoreCase = true)) {
                val res = otaManager.downloadHotUpdate(url) { progress ->
                    CoroutineScope(Dispatchers.Main).launch {
                        onEvaluateJs?.invoke("if (typeof window.onOtaProgress === 'function') window.onOtaProgress($progress);")
                    }
                }
                withContext(Dispatchers.Main) {
                    if (res.isSuccess) {
                        onEvaluateJs?.invoke("if (typeof window.onOtaComplete === 'function') window.onOtaComplete(true, 'hot');")
                    } else {
                        val errMsg = JSONObject.quote(res.exceptionOrNull()?.message ?: "Fallo de descarga")
                        onEvaluateJs?.invoke("if (typeof window.onOtaComplete === 'function') window.onOtaComplete(false, $errMsg);")
                    }
                }
            } else {
                val res = otaManager.downloadApkUpdate(url) { progress ->
                    CoroutineScope(Dispatchers.Main).launch {
                        onEvaluateJs?.invoke("if (typeof window.onOtaProgress === 'function') window.onOtaProgress($progress);")
                    }
                }
                withContext(Dispatchers.Main) {
                    if (res.isSuccess) {
                        val apkFile = res.getOrNull()!!
                        val installed = otaManager.installApk(apkFile)
                        onEvaluateJs?.invoke("if (typeof window.onOtaComplete === 'function') window.onOtaComplete($installed, 'apk');")
                    } else {
                        val errMsg = JSONObject.quote(res.exceptionOrNull()?.message ?: "Fallo al descargar APK")
                        onEvaluateJs?.invoke("if (typeof window.onOtaComplete === 'function') window.onOtaComplete(false, $errMsg);")
                    }
                }
            }
        }
    }

    /**
     * Soft reloads the active JoyOS WebView interface from local storage.
     */
    @JavascriptInterface
    fun reloadUi() {
        CoroutineScope(Dispatchers.Main).launch {
            onReloadUi?.invoke()
        }
    }

    /**
     * Returns true if the automotive head unit firmware is pre-rooted.
     */
    @JavascriptInterface
    fun isDeviceRooted(): Boolean {
        return hardwareOptimizer?.isDeviceRooted() ?: false
    }

    /**
     * Executes in-app hardware & system optimizations:
     * Disables OEM bloatware launchers, removes safe volume limiter, accelerates animations to 0.5x,
     * opens ADB Wi-Fi port 5555, and reclaims RAM.
     */
    @JavascriptInterface
    fun optimizeSystemHardware() {
        if (hardwareOptimizer == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val report = hardwareOptimizer.runOptimization()
            withContext(Dispatchers.Main) {
                onEvaluateJs?.invoke("if (typeof window.onHardwareOptimizationComplete === 'function') window.onHardwareOptimizationComplete(${report.toJson()});")
            }
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
