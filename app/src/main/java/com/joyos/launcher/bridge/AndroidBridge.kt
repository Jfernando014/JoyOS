package com.joyos.launcher.bridge

import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.media.MediaMetadataRetriever
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import com.joyos.launcher.services.GpsData
import com.joyos.launcher.services.JoyAudioDspEngine
import com.joyos.launcher.services.JoyHardwareOptimizer
import com.joyos.launcher.services.ObdState
import com.joyos.launcher.services.OtaUpdateManager
import com.joyos.launcher.services.TpmsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Native bridge interface between JoyOS WebView UI and Android automotive subsystem.
 * Accessible from JavaScript as `window.AndroidBridge`.
 */
class AndroidBridge(
    private val context: Context,
    private val dspEngine: JoyAudioDspEngine? = null,
    private val otaManager: OtaUpdateManager? = null,
    private val hardwareOptimizer: JoyHardwareOptimizer? = null,
    private val getGpsData: (() -> GpsData)? = null,
    private val getObdState: (() -> ObdState)? = null,
    private val getTpmsState: (() -> TpmsState)? = null,
    private val onSetAlertThresholds: ((Int, Int) -> Unit)? = null,
    private val onReconnectHardware: (() -> Unit)? = null,
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
    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    /**
     * Returns real connected Wi-Fi SSID and power state.
     */
    @JavascriptInterface
    fun getWifiStatus(): String {
        return try {
            val enabled = wifiManager?.isWifiEnabled ?: false
            val info = wifiManager?.connectionInfo
            val ssid = if (info != null && info.networkId != -1 && !info.ssid.isNullOrBlank()) {
                val clean = info.ssid.replace("\"", "").trim()
                if (clean == "<unknown ssid>" || clean.isEmpty()) "Wi-Fi Conectado" else clean
            } else {
                "Desconectado"
            }
            val json = JSONObject()
            json.put("enabled", enabled)
            json.put("connected", ssid != "Desconectado")
            json.put("ssid", ssid)
            json.toString()
        } catch (_: Throwable) {
            "{\"enabled\":false,\"connected\":false,\"ssid\":\"Desconectado\"}"
        }
    }

    /**
     * Checks if a BluetoothDevice currently has an active ACL/profile connection without relying
     * on async ProfileProxy setup (supported across Android 8.1+ AC8227L and API 34).
     */
    private fun isBtDeviceActivelyConnected(dev: BluetoothDevice): Boolean {
        return try {
            val m = dev.javaClass.getMethod("isConnected")
            (m.invoke(dev) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns real active Bluetooth connection state and connected device name.
     */
    @JavascriptInterface
    fun getBluetoothStatus(): String {
        return try {
            val adapter = bluetoothAdapter
            val enabled = adapter?.isEnabled ?: false
            if (!enabled || adapter == null) {
                return "{\"enabled\":false,\"connected\":false,\"device\":\"Bluetooth apagado\",\"pairedCount\":0}"
            }

            val bonded = try { adapter.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
            val activeBonded = bonded.firstOrNull { isBtDeviceActivelyConnected(it) }

            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val gattConnected = try {
                @Suppress("DEPRECATION")
                bm?.getConnectedDevices(BluetoothProfile.GATT)?.firstOrNull()
            } catch (_: Throwable) {
                null
            }

            val a2dpState = try { adapter.getProfileConnectionState(BluetoothProfile.A2DP) } catch (_: Throwable) { BluetoothProfile.STATE_DISCONNECTED }
            val hfpState = try { adapter.getProfileConnectionState(BluetoothProfile.HEADSET) } catch (_: Throwable) { BluetoothProfile.STATE_DISCONNECTED }
            val profileConnected = a2dpState == BluetoothProfile.STATE_CONNECTED || hfpState == BluetoothProfile.STATE_CONNECTED
            val obdConnected = getObdState?.invoke()?.connected == true

            val isConnected = activeBonded != null || gattConnected != null || profileConnected || lastBtPlaying
            val deviceName = activeBonded?.name
                ?: gattConnected?.name
                ?: (if (profileConnected) bonded.firstOrNull()?.name else null)
                ?: (if (obdConnected) (getObdState?.invoke()?.deviceName?.ifBlank { "OBD2" } ?: "OBD2") else null)
                ?: (if (bonded.isNotEmpty()) "${bonded.size} vinculado(s)" else "Sin dispositivo")

            val json = JSONObject()
            json.put("enabled", true)
            json.put("connected", isConnected)
            json.put("obdConnected", obdConnected)
            json.put("device", deviceName)
            json.put("pairedCount", bonded.size)
            json.toString()
        } catch (_: Throwable) {
            "{\"enabled\":false,\"connected\":false,\"device\":\"Sin dispositivo\",\"pairedCount\":0}"
        }
    }

    /**
     * Returns all paired Bluetooth devices as a JSON array with live connection indicator.
     */
    @JavascriptInterface
    fun getBluetoothBondedDevices(): String {
        return try {
            val bonded = try { bluetoothAdapter?.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
            val arr = JSONArray()
            for (dev in bonded) {
                val obj = JSONObject()
                val name = dev.name ?: "Dispositivo Bluetooth"
                obj.put("name", name)
                obj.put("address", dev.address ?: "")
                obj.put("connected", isBtDeviceActivelyConnected(dev))
                obj.put("type", if (name.contains("OBD", true) || name.contains("ELM", true) || name.contains("VGATE", true) || name.contains("VLINK", true) || name.contains("V-LINK", true) || name.contains("ICAR", true)) "obd" else "phone")
                arr.put(obj)
            }
            arr.toString()
        } catch (_: Throwable) {
            "[]"
        }
    }

    /**
     * Returns real car battery/alternator voltage from OBD2 ATRV first, falling back to real 12V ADC
     * only if exposed by hardware; never emits fake static "12.4" when unavailable.
     */
    @JavascriptInterface
    fun getBatteryStatus(): String {
        return try {
            val obdV = getObdState?.invoke()?.batteryVoltage ?: -1.0
            if (obdV > 5.0) {
                val json = JSONObject()
                json.put("level", 100)
                json.put("voltage", String.format(Locale.US, "%.1f", obdV))
                json.put("source", "obd2")
                return json.toString()
            }

            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bIntent = context.registerReceiver(null, iFilter)
            val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val voltMv = bIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val voltStr = if (voltMv in 5000..18000) {
                String.format(Locale.US, "%.1f", voltMv / 1000.0)
            } else {
                ""
            }
            val json = JSONObject()
            json.put("level", pct)
            json.put("voltage", voltStr)
            json.put("source", if (voltStr.isNotEmpty()) "adc" else "none")
            json.toString()
        } catch (_: Throwable) {
            "{\"level\":-1,\"voltage\":\"\",\"source\":\"none\"}"
        }
    }

    /**
     * Reads real CPU thermal temperature from kernel thermal zones; returns -1 when unreadable.
     */
    @JavascriptInterface
    fun getCpuTemperature(): Int {
        return try {
            val paths = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp",
                "/proc/mtktz/mtktscpu"
            )
            for (p in paths) {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val raw = f.readText().trim().toIntOrNull() ?: continue
                    val deg = if (raw > 1000) raw / 1000 else raw
                    if (deg in 1..125) return deg
                }
            }
            -1
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Dispatches real hardware media keys (Play/Pause, Next, Prev) to whichever music app is running.
     */
    @JavascriptInterface
    fun sendMediaKeyEvent(action: String) {
        try {
            val key = when (action.lowercase()) {
                "play", "pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val down = KeyEvent(KeyEvent.ACTION_DOWN, key)
            val up = KeyEvent(KeyEvent.ACTION_UP, key)
            audioManager?.dispatchMediaKeyEvent(down)
            audioManager?.dispatchMediaKeyEvent(up)
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Media key event error: ${e.message}")
        }
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
            String.format(Locale.US, "%.2f GB", usedGb).replace('.', ',')
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "RAM optimization handled: ${e.message}")
            "-- GB"
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
     * Opens a media or document file using the system's default media player or viewer.
     */
    @JavascriptInterface
    fun openFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            val ext = file.extension.lowercase()
            val mimeType = when (ext) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "m4a", "aac" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                "mp4", "mkv", "avi" -> "video/*"
                "png", "jpg", "jpeg" -> "image/*"
                else -> "*/*"
            }
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error opening file: ${e.message}")
            false
        }
    }

    /**
     * Finds and launches the vehicle's native stock Radio FM application.
     */
    @JavascriptInterface
    fun openNativeRadio(): Boolean {
        val radioPkgs = listOf(
            "com.ts.fmradio",
            "com.xyauto.radio",
            "com.android.fmradio",
            "com.microntek.fmradio",
            "com.forfan.fmradio",
            "com.ww.fmradio",
            "com.tw.radio",
            "com.sds.radio",
            "com.autonavi.radio"
        )
        for (pkg in radioPkgs) {
            if (launchApp(pkg)) return true
        }
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            for (p in packages) {
                val pkgName = p.packageName.lowercase()
                if ((pkgName.contains("radio") || pkgName.contains("fmradio")) && !pkgName.contains("joyos")) {
                    if (launchApp(p.packageName)) return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    /**
     * Sends frequency tune broadcast intents to automotive MCU radio services.
     */
    @JavascriptInterface
    fun tuneRadio(frequency: Double): Boolean {
        return try {
            val freqKhz = (frequency * 1000).toInt()
            val freqMhz = frequency.toFloat()
            val tuneIntents = listOf(
                Intent("com.android.fmradio.ACTION_TUNE").putExtra("frequency", freqKhz),
                Intent("com.microntek.radio.frequency").putExtra("frequency", freqKhz),
                Intent("com.ts.fmradio.ACTION_TUNE").putExtra("frequency", freqKhz),
                Intent("xyauto.radio.tune").putExtra("frequency", freqMhz),
                Intent("com.autonavi.radio.tune").putExtra("freq", freqKhz)
            )
            for (intent in tuneIntents) {
                try {
                    context.sendBroadcast(intent)
                } catch (_: Throwable) {}
            }
            true
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error tuning radio: ${e.message}")
            false
        }
    }

    /**
     * Triggers hardware FM radio station seek (up/down) on automotive MCU tuners.
     */
    @JavascriptInterface
    fun seekRadio(direction: Int): Boolean {
        return try {
            val up = direction >= 0
            val seekIntents = listOf(
                Intent(if (up) "com.android.fmradio.ACTION_SEEK_UP" else "com.android.fmradio.ACTION_SEEK_DOWN"),
                Intent("com.ts.fmradio.ACTION_SEEK").putExtra("direction", if (up) 1 else -1),
                Intent("com.microntek.radio.seek").putExtra("direction", if (up) 1 else -1),
                Intent("xyauto.radio.seek").putExtra("dir", if (up) 1 else -1),
                Intent("com.autochips.fmradio.intent.action.SEEK").putExtra("seek_up", up)
            )
            for (intent in seekIntents) {
                try {
                    context.sendBroadcast(intent)
                } catch (_: Throwable) {}
            }
            true
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error seeking radio: ${e.message}")
            false
        }
    }

    /**
     * Opens an external URL in the Android browser (e.g., RUNT, Grupo R5, SIMIT).
     */
    @JavascriptInterface
    fun openExternalUrl(url: String): Boolean {
        return try {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return false
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error opening external URL: ${e.message}")
            false
        }
    }

    /**
     * Launches turn-by-turn navigation to exact coordinates in Waze, Google Maps, or any geo handler.
     */
    @JavascriptInterface
    fun navigateToLocation(lat: Double, lon: Double, label: String, preferredApp: String): Boolean {
        return try {
            val safeLabel = Uri.encode(label.ifBlank { "Destino" })
            val targets = if (preferredApp == "waze") {
                listOf(
                    Pair(Uri.parse("waze://?ll=$lat,$lon&navigate=yes"), "com.waze"),
                    Pair(Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes"), null),
                    Pair(Uri.parse("geo:$lat,$lon?q=$lat,$lon($safeLabel)"), null)
                )
            } else {
                listOf(
                    Pair(Uri.parse("google.navigation:q=$lat,$lon"), "com.google.android.apps.maps"),
                    Pair(Uri.parse("geo:$lat,$lon?q=$lat,$lon($safeLabel)"), null),
                    Pair(Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"), null)
                )
            }
            for ((uri, pkg) in targets) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (pkg != null) setPackage(pkg)
                    }
                    context.startActivity(intent)
                    return true
                } catch (_: Throwable) {}
            }
            false
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error navigating to location: ${e.message}")
            false
        }
    }

    /**
     * Saves arbitrary JSON state persistently in SharedPreferences so data survives WebView cache clears.
     */
    @JavascriptInterface
    fun savePersistentJson(key: String, json: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences("joyos_persistent_store", Context.MODE_PRIVATE)
            prefs.edit().putString(key, json).apply()
            true
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error saving persistent JSON: ${e.message}")
            false
        }
    }

    /**
     * Loads previously persisted JSON string from SharedPreferences, or empty string if absent.
     */
    @JavascriptInterface
    fun loadPersistentJson(key: String): String {
        return try {
            val prefs = context.getSharedPreferences("joyos_persistent_store", Context.MODE_PRIVATE)
            prefs.getString(key, "") ?: ""
        } catch (_: Throwable) {
            ""
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

    /**
     * Opens native Android System Settings. Never traps the user.
     */
    @JavascriptInterface
    fun openAndroidSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            launchApp("com.android.settings")
        }
    }

    /**
     * Emergency Exit / Launcher Switcher.
     * Opens Android's default home selector so the user can easily return to the stock launcher.
     */
    @JavascriptInterface
    fun openHomeSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Throwable) {
                openAndroidSettings()
            }
        }
    }

    /**
     * Opens native Wi-Fi settings for wireless OBD2 / internet hotspot.
     */
    @JavascriptInterface
    fun openWifiSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            openAndroidSettings()
        }
    }

    /**
     * Opens native Bluetooth settings to pair phone or OBD2 ELM327 scanner.
     */
    @JavascriptInterface
    fun openBluetoothSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            openAndroidSettings()
        }
    }

    /**
     * Reboots the Android head unit.
     */
    @JavascriptInterface
    fun rebootDevice() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
        } catch (_: Throwable) {
            try {
                Runtime.getRuntime().exec("reboot")
            } catch (_: Throwable) {
                Toast.makeText(context, "Reinicio requiere permisos de sistema", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Re-enables all factory OEM launchers and restores standard animations (Safety Rollback).
     */
    @JavascriptInterface
    fun restoreFactoryLaunchers(): Boolean {
        return try {
            val packages = listOf(
                "com.android.launcher3",
                "com.fyt.launcher",
                "com.ts.main",
                "com.xyauto.launcher",
                "com.autochips.carplay"
            )
            packages.forEach { pkg ->
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "pm enable $pkg")).waitFor() } catch (_: Throwable) {}
            }
            try { Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global window_animation_scale 1.0")).waitFor() } catch (_: Throwable) {}
            try { Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global transition_animation_scale 1.0")).waitFor() } catch (_: Throwable) {}
            try { Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global animator_duration_scale 1.0")).waitFor() } catch (_: Throwable) {}
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Scans real files from connected USB drives in glovebox or internal storage.
     * Returns JSON array of real APK and media files.
     */
    @JavascriptInterface
    fun getStorageFiles(driveType: String): String {
        return try {
            val fileList = JSONArray()
            val targetDirs = mutableListOf<File>()

            if (driveType.equals("usb", ignoreCase = true)) {
                // Potential USB OTG mount points in automotive MediaTek AC8227L units
                val storageRoot = File("/storage")
                if (storageRoot.exists() && storageRoot.isDirectory) {
                    storageRoot.listFiles()?.forEach { file ->
                        val name = file.name.lowercase()
                        if (file.isDirectory && name != "emulated" && name != "self" && file.canRead()) {
                            targetDirs.add(file)
                        }
                    }
                }
                val mntMedia = File("/mnt/media_rw")
                if (mntMedia.exists() && mntMedia.isDirectory) {
                    mntMedia.listFiles()?.forEach { file ->
                        if (file.isDirectory && file.canRead() && !targetDirs.contains(file)) {
                            targetDirs.add(file)
                        }
                    }
                }
                // Check removable external directories via Context
                context.getExternalFilesDirs(null)?.forEach { dir ->
                    if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                        var root: File? = dir
                        while (root?.parentFile != null && root.parentFile?.absolutePath != "/storage") {
                            root = root.parentFile
                        }
                        if (root != null && root.canRead() && !targetDirs.contains(root)) {
                            targetDirs.add(root)
                        }
                    }
                }
                listOf("/storage/usb0", "/storage/usb1", "/storage/usbotg", "/mnt/usb_storage").forEach { p ->
                    val f = File(p)
                    if (f.exists() && f.isDirectory && f.canRead() && !targetDirs.contains(f)) {
                        targetDirs.add(f)
                    }
                }
            } else {
                // Internal storage roots
                targetDirs.add(Environment.getExternalStorageDirectory())
                targetDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                targetDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
            }

            val validExtensions = setOf("apk", "mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "mp4", "mkv", "avi", "pdf")
            val foundFiles = mutableListOf<File>()

            fun scanDir(dir: File, depth: Int) {
                if (depth > 3 || foundFiles.size >= 80) return
                val entries = dir.listFiles() ?: return
                for (entry in entries) {
                    if (entry.isDirectory && !entry.name.startsWith(".")) {
                        scanDir(entry, depth + 1)
                    } else if (entry.isFile) {
                        val ext = entry.extension.lowercase()
                        if (validExtensions.contains(ext)) {
                            foundFiles.add(entry)
                        }
                    }
                }
            }

            for (dir in targetDirs) {
                scanDir(dir, 0)
            }

            for (file in foundFiles) {
                val ext = file.extension.lowercase()
                val isApk = ext == "apk"
                val isAudio = ext in setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "wma")
                val item = JSONObject().apply {
                    put("id", "f_" + file.absolutePath.hashCode())
                    put("name", file.name)
                    put("path", file.absolutePath)
                    put("size", formatFileSize(file.length()))
                    put("bytes", file.length())
                    put("ext", ext)
                    put("isApk", isApk)
                    put("isAudio", isAudio)
                    put("drive", driveType)
                    put("lastModified", file.lastModified())
                }
                fileList.put(item)
            }

            fileList.toString()
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error scanning storage files: ${e.message}")
            "[]"
        }
    }

    /**
     * Returns real-time GPS location, bearing, speed, and fix state.
     */
    @JavascriptInterface
    fun getGpsLocation(): String {
        return try {
            val data = getGpsData?.invoke() ?: GpsData()
            val json = JSONObject().apply {
                put("lat", data.latitude)
                put("lon", data.longitude)
                put("latitude", data.latitude)
                put("longitude", data.longitude)
                put("speed", data.speedKmh)
                put("bearing", data.bearing)
                put("altitude", data.altitude)
                put("hasFix", data.hasFix)
                put("tripKm", data.tripKm)
            }
            json.toString()
        } catch (_: Throwable) {
            "{\"lat\":4.6534,\"lon\":-74.0836,\"latitude\":4.6534,\"longitude\":-74.0836,\"speed\":0,\"bearing\":0,\"altitude\":2600,\"hasFix\":false,\"tripKm\":0}"
        }
    }

    /**
     * Scans automotive USB flash drives and internal storage for audio files.
     * Extracts title, artist, album, duration, and file URI for the embedded player.
     */
    @JavascriptInterface
    fun getMusicFiles(): String {
        return try {
            val musicList = JSONArray()
            val targetDirs = mutableListOf<File>()

            // 1. Removable USB paths
            try {
                val extDirs = androidx.core.content.ContextCompat.getExternalFilesDirs(context, null)
                for (dir in extDirs) {
                    if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                        var root: File? = dir
                        while (root?.parentFile != null && root.parentFile?.absolutePath != "/storage") {
                            root = root.parentFile
                        }
                        if (root != null && root.canRead() && !targetDirs.contains(root)) {
                            targetDirs.add(root)
                        }
                    }
                }
            } catch (_: Throwable) {}

            listOf("/storage/usb0", "/storage/usb1", "/storage/usbotg", "/mnt/usb_storage", "/mnt/media_rw").forEach { p ->
                val f = File(p)
                if (f.exists() && f.isDirectory && f.canRead() && !targetDirs.contains(f)) {
                    targetDirs.add(f)
                }
            }

            // 2. Internal storage music & download folders
            listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                File("/storage/emulated/0/Music"),
                File("/storage/emulated/0/Download")
            ).forEach { f ->
                if (f != null && f.exists() && f.isDirectory && f.canRead() && !targetDirs.contains(f)) {
                    targetDirs.add(f)
                }
            }

            val validAudioExts = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg")
            val foundAudio = mutableListOf<File>()

            fun scanDir(dir: File, depth: Int) {
                if (depth > 4 || foundAudio.size >= 120) return
                val entries = dir.listFiles() ?: return
                for (entry in entries) {
                    if (entry.isDirectory && !entry.name.startsWith(".")) {
                        scanDir(entry, depth + 1)
                    } else if (entry.isFile) {
                        val ext = entry.extension.lowercase()
                        if (validAudioExts.contains(ext)) {
                            foundAudio.add(entry)
                        }
                    }
                }
            }

            for (dir in targetDirs) {
                scanDir(dir, 0)
            }

            val mmr = MediaMetadataRetriever()
            for (file in foundAudio) {
                var title = file.nameWithoutExtension
                var artist = "Desconocido"
                var album = if (file.absolutePath.contains("usb") || file.absolutePath.contains("mnt")) "Memoria USB" else "Almacenamiento Local"
                var durationSec = 210

                try {
                    mmr.setDataSource(file.absolutePath)
                    val metaTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val metaArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    val metaAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    val metaDur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

                    if (!metaTitle.isNullOrBlank()) title = metaTitle.trim()
                    if (!metaArtist.isNullOrBlank()) artist = metaArtist.trim()
                    if (!metaAlbum.isNullOrBlank()) album = metaAlbum.trim()
                    val durMs = metaDur?.toLongOrNull() ?: 0L
                    if (durMs > 0) durationSec = (durMs / 1000).toInt()
                } catch (_: Throwable) {
                    if (title.contains(" - ")) {
                        val parts = title.split(" - ", limit = 2)
                        artist = parts[0].trim()
                        title = parts[1].trim()
                    }
                }

                val item = JSONObject().apply {
                    put("id", "m_" + file.absolutePath.hashCode())
                    put("title", title)
                    put("artist", artist)
                    put("album", album)
                    put("durationSec", durationSec)
                    put("path", file.absolutePath)
                    put("uri", "file://" + file.absolutePath)
                    put("source", if (file.absolutePath.contains("usb") || file.absolutePath.contains("mnt")) "usb" else "internal")
                    put("size", formatFileSize(file.length()))
                }
                musicList.put(item)
            }
            try { mmr.release() } catch (_: Throwable) {}

            musicList.toString()
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error scanning music files: ${e.message}")
            "[]"
        }
    }

    // --- LIVE BLUETOOTH AVRCP / MEDIA SESSION TRACKING ---

    @Volatile private var lastBtTitle: String = ""
    @Volatile private var lastBtArtist: String = ""
    @Volatile private var lastBtAlbum: String = ""
    @Volatile private var lastBtDurationSec: Int = 210
    @Volatile private var lastBtPlaying: Boolean = false
    @Volatile private var lastBtActionSource: String = ""

    fun updateLiveBtMetadata(
        title: String?,
        artist: String?,
        album: String?,
        durationSec: Int,
        isPlaying: Boolean?,
        sourceAction: String
    ) {
        if (!title.isNullOrBlank()) lastBtTitle = title
        if (!artist.isNullOrBlank()) lastBtArtist = artist
        if (!album.isNullOrBlank()) lastBtAlbum = album
        if (durationSec > 0) lastBtDurationSec = durationSec
        if (isPlaying != null) lastBtPlaying = isPlaying
        lastBtActionSource = sourceAction

        val json = JSONObject().apply {
            put("title", lastBtTitle)
            put("artist", if (lastBtArtist.isNotBlank()) lastBtArtist else "Bluetooth Audio")
            put("album", if (lastBtAlbum.isNotBlank()) lastBtAlbum else "A2DP Stream")
            put("durationSec", if (lastBtDurationSec > 0) lastBtDurationSec else 210)
            put("playing", lastBtPlaying)
            put("source", lastBtActionSource)
        }
        onEvaluateJs?.invoke("if (typeof window.onBtMusicMetadata === 'function') window.onBtMusicMetadata($json);")
    }

    @JavascriptInterface
    fun getBtMusicMetadata(): String {
        return try {
            val json = JSONObject().apply {
                put("title", lastBtTitle)
                put("artist", lastBtArtist)
                put("album", lastBtAlbum)
                put("durationSec", lastBtDurationSec)
                put("playing", lastBtPlaying)
                put("source", lastBtActionSource)
            }
            json.toString()
        } catch (_: Throwable) {
            "{}"
        }
    }

    /**
     * Returns real filesystem capacity (free GB, total GB, mount path, mounted state) via StatFs.
     * Eliminates burned-in "Kingston DataTraveler 32 GB" or "24,8 GB libres" strings.
     */
    @JavascriptInterface
    fun getStorageStats(driveType: String): String {
        return try {
            var targetDir: File? = null
            if (driveType.equals("usb", ignoreCase = true)) {
                val storageRoot = File("/storage")
                if (storageRoot.exists() && storageRoot.isDirectory) {
                    storageRoot.listFiles()?.forEach { file ->
                        val name = file.name.lowercase()
                        if (targetDir == null && file.isDirectory && name != "emulated" && name != "self" && file.canRead()) {
                            targetDir = file
                        }
                    }
                }
                if (targetDir == null) {
                    listOf("/storage/usb0", "/storage/usb1", "/storage/usbotg", "/mnt/usb_storage", "/mnt/media_rw").forEach { p ->
                        val f = File(p)
                        if (targetDir == null && f.exists() && f.isDirectory && f.canRead() && (f.listFiles()?.isNotEmpty() == true)) {
                            targetDir = f
                        }
                    }
                }
            } else {
                targetDir = Environment.getExternalStorageDirectory()
            }

            if (targetDir == null || !targetDir!!.exists()) {
                return JSONObject().apply {
                    put("mounted", false)
                    put("path", if (driveType.equals("usb", true)) "/storage/usb0" else "/storage/emulated/0")
                    put("label", if (driveType.equals("usb", true)) "Sin unidad USB insertada" else "Almacenamiento Interno")
                    put("freeGb", "0,0")
                    put("totalGb", "0,0")
                    put("usedPercent", 0)
                }.toString()
            }

            val stat = StatFs(targetDir!!.absolutePath)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
            val totalGb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val freeGb = freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val usedPct = if (totalBytes > 0) ((usedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0

            JSONObject().apply {
                put("mounted", totalBytes > 0)
                put("path", targetDir!!.absolutePath)
                put("label", targetDir!!.name)
                put("freeGb", String.format(Locale.US, "%.1f", freeGb).replace('.', ','))
                put("totalGb", String.format(Locale.US, "%.1f", totalGb).replace('.', ','))
                put("usedPercent", usedPct)
            }.toString()
        } catch (e: Throwable) {
            JSONObject().apply {
                put("mounted", false)
                put("path", "/storage")
                put("label", "No disponible")
                put("freeGb", "0,0")
                put("totalGb", "0,0")
                put("usedPercent", 0)
            }.toString()
        }
    }

    /**
     * Returns real system RAM usage (used, available, total) from ActivityManager.MemoryInfo.
     */
    @JavascriptInterface
    fun getSystemRamInfo(): String {
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val totalBytes = memInfo.totalMem
            val availBytes = memInfo.availMem
            val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)
            val usedGb = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val totalGb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val availMb = availBytes / (1024 * 1024)
            JSONObject().apply {
                put("usedGb", String.format(Locale.US, "%.2f GB", usedGb).replace('.', ','))
                put("totalGb", String.format(Locale.US, "%.1f GB", totalGb).replace('.', ','))
                put("availMb", availMb)
            }.toString()
        } catch (_: Throwable) {
            "{\"usedGb\":\"-- GB\",\"totalGb\":\"-- GB\",\"availMb\":0}"
        }
    }

    /**
     * In-App Reverse Engineering (REA) Hardware & Firmware Inspector for ZXDZ_01 (AC8227L / cm69 / WJ).
     * Discovers all OEM MCU, Radio, Bluetooth, CarPlay/ZLink packages, system properties, and serial nodes
     * directly on the head unit without requiring an external PC or USB ADB connection.
     */
    @JavascriptInterface
    fun inspectOemHardware(): String {
        return try {
            val result = JSONObject()
            result.put("model", Build.MODEL ?: "ZXDZ_01")
            result.put("display", Build.DISPLAY ?: "WJ_2024.03.12")
            result.put("board", Build.BOARD ?: "AC8227L")
            result.put("hardware", Build.HARDWARE ?: "mt8227")
            result.put("androidVer", Build.VERSION.RELEASE ?: "8.1.0")
            result.put("sdkInt", Build.VERSION.SDK_INT)

            // 1. Read key automotive system properties via getprop
            val propsObj = JSONObject()
            val propKeys = listOf(
                "ro.build.display.id",
                "ro.custom.build.version",
                "sys.mcu.version",
                "ro.product.model",
                "ro.product.board",
                "ro.mediatek.platform",
                "persist.sys.mcu.version",
                "ro.fyt.platform",
                "ro.xyauto.platform"
            )
            for (key in propKeys) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("getprop", key))
                    val line = proc.inputStream.bufferedReader().readLine()?.trim() ?: ""
                    proc.destroy()
                    if (line.isNotBlank()) propsObj.put(key, line)
                } catch (_: Throwable) {}
            }
            result.put("properties", propsObj)

            // 2. Discover installed OEM / MCU / Radio / BT / CarPlay packages
            val oemPackages = JSONArray()
            val allPkgs = try { packageManager.getInstalledPackages(0) } catch (_: Throwable) { emptyList() }
            val keywords = listOf(
                "autochips", "xyauto", "ts.", "fyt", "microntek", "radio", "fmradio",
                "bt", "bluetooth", "mcu", "canbus", "zlink", "tlink", "carplay", "wj", "atc"
            )
            for (pkgInfo in allPkgs) {
                val pkgName = pkgInfo.packageName ?: continue
                val lower = pkgName.lowercase()
                if (keywords.any { lower.contains(it) }) {
                    val label = try {
                        pkgInfo.applicationInfo?.loadLabel(packageManager)?.toString() ?: pkgName
                    } catch (_: Throwable) { pkgName }
                    val enabled = pkgInfo.applicationInfo?.enabled ?: true
                    oemPackages.put(JSONObject().apply {
                        put("package", pkgName)
                        put("label", label)
                        put("enabled", enabled)
                    })
                }
            }
            result.put("oemPackages", oemPackages)

            // 3. Probe serial / MCU / FM hardware nodes (/dev/ttyMT*, /dev/ttyS*, /dev/fm)
            val serialNodes = JSONArray()
            val candidateNodes = listOf(
                "/dev/ttyMT0", "/dev/ttyMT1", "/dev/ttyMT2", "/dev/ttyMT3",
                "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/fm", "/dev/mcu"
            )
            for (nodePath in candidateNodes) {
                val f = File(nodePath)
                if (f.exists()) {
                    serialNodes.put(JSONObject().apply {
                        put("path", nodePath)
                        put("readable", f.canRead())
                        put("writable", f.canWrite())
                    })
                }
            }
            result.put("hardwareNodes", serialNodes)
            result.put("btMetadata", JSONObject(getBtMusicMetadata()))

            // 4. Connected USB Host devices (for diagnosing 433.92 MHz TPMS receiver VID:PID)
            val usbArray = JSONArray()
            try {
                val usbMgr = context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
                usbMgr?.deviceList?.values?.forEach { dev ->
                    usbArray.put(JSONObject().apply {
                        put("vid", String.format(Locale.US, "0x%04X", dev.vendorId))
                        put("pid", String.format(Locale.US, "0x%04X", dev.productId))
                        put("product", dev.productName ?: "USB Device")
                        put("manufacturer", dev.manufacturerName ?: "")
                        put("deviceClass", dev.deviceClass)
                        put("interfaces", dev.interfaceCount)
                        put("hasPermission", usbMgr.hasPermission(dev))
                    })
                }
            } catch (_: Throwable) {}
            result.put("usbDevices", usbArray)

            // 5. Live OBD2 & USB TPMS diagnostics
            val obd = getObdState?.invoke()
            result.put("obd", JSONObject().apply {
                put("connected", obd?.connected ?: false)
                put("transport", obd?.transport ?: "disconnected")
                put("deviceName", obd?.deviceName ?: "")
                put("rpm", obd?.rpm ?: 0)
                put("coolantTempC", obd?.coolantTempC ?: -99)
                put("batteryVoltage", obd?.batteryVoltage ?: -1.0)
                put("lastError", obd?.lastError ?: "")
            })

            val tpms = getTpmsState?.invoke()
            result.put("tpms", JSONObject().apply {
                put("connected", tpms?.connected ?: false)
                put("source", tpms?.source ?: "disconnected")
                put("deviceLabel", tpms?.deviceLabel ?: "")
                put("lastRawHex", tpms?.lastRawHex ?: "")
                put("flPsi", tpms?.flPsi ?: -1)
                put("frPsi", tpms?.frPsi ?: -1)
                put("rlPsi", tpms?.rlPsi ?: -1)
                put("rrPsi", tpms?.rrPsi ?: -1)
            })

            // 6. Display metrics
            val dm = context.resources.displayMetrics
            result.put("screen", JSONObject().apply {
                put("widthPx", dm.widthPixels)
                put("heightPx", dm.heightPixels)
                put("densityDpi", dm.densityDpi)
                put("density", dm.density.toDouble())
            })

            result.toString()
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Error in inspectOemHardware: ${e.message}")
            "{\"model\":\"ZXDZ_01\",\"error\":\"${e.message}\"}"
        }
    }

    @JavascriptInterface
    fun setAlertThresholds(lowPsi: Int, hotTempC: Int) {
        onSetAlertThresholds?.invoke(lowPsi, hotTempC)
    }

    @JavascriptInterface
    fun reconnectHardware() {
        onReconnectHardware?.invoke()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    private fun categorizeApp(pkg: String): String {
        val lower = pkg.lowercase()
        return when {
            lower.contains("map") || lower.contains("waze") || lower.contains("navigation") || lower.contains("here") -> "nav"
            lower.contains("music") || lower.contains("spotify") || lower.contains("radio") || lower.contains("audio") || lower.contains("sound") -> "media"
            lower.contains("zlink") || lower.contains("auto") || lower.contains("carplay") || lower.contains("tlink") -> "link"
            lower.contains("torque") || lower.contains("obd") || lower.contains("tpms") || lower.contains("carscanner") -> "vehicle"
            lower.contains("setting") || lower.contains("config") -> "settings"
            else -> "tools"
        }
    }
}
