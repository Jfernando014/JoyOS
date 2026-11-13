package com.joyos.launcher.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.joyos.launcher.services.GpsData
import com.joyos.launcher.services.JoyAudioDspEngine
import com.joyos.launcher.services.JoyHardwareOptimizer
import com.joyos.launcher.services.JoyMediaArtworkHelper
import com.joyos.launcher.services.JoyMediaNotificationListener
import com.joyos.launcher.services.ObdState
import com.joyos.launcher.services.OtaUpdateManager
import com.joyos.launcher.services.TpmsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val onSetGpsSource: ((String) -> Unit)? = null,
    private val onResetTrip: (() -> Unit)? = null,
    private val onSetScreenTimeout: ((String) -> Unit)? = null,
    private val onRequestPermissions: (() -> Unit)? = null,
    private val onClearWebCache: (() -> Unit)? = null,
    private val onReloadUi: (() -> Unit)? = null,
    private val onEvaluateJs: ((String) -> Unit)? = null,
    private val onMediaTransportAction: ((String) -> Boolean)? = null,
    private val onSeekMediaPosition: ((Long) -> Boolean)? = null,
    private val onConnectBluetoothDevice: ((String) -> Boolean)? = null,
    private val onDisconnectBluetoothDevice: ((String) -> Boolean)? = null,
    private val onAnswerCallAction: (() -> Boolean)? = null,
    private val onEndCallAction: (() -> Boolean)? = null,
    private val onDialCallAction: ((String) -> Boolean)? = null
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

    private fun isInternetOnline(): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            false
        }
    }

    @Volatile private var hfpBatteryPct: Int = -1
    @Volatile private var hfpSignalBars: Int = -1
    @Volatile private var hfpActiveAddress: String = ""

    fun updateBluetoothTelemetry(batteryPct: Int? = null, signalBars: Int? = null, deviceAddress: String? = null) {
        if (batteryPct != null) hfpBatteryPct = batteryPct.coerceIn(-1, 100)
        if (signalBars != null) hfpSignalBars = signalBars.coerceIn(-1, 4)
        if (deviceAddress != null) hfpActiveAddress = deviceAddress
    }

    private fun isHotspotActive(): Boolean {
        return try {
            val wm = wifiManager ?: return false
            val enabledByBool = try {
                val m = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
                m.isAccessible = true
                (m.invoke(wm) as? Boolean) == true
            } catch (_: Throwable) {
                false
            }
            if (enabledByBool) return true
            try {
                val mState = wm.javaClass.getDeclaredMethod("getWifiApState")
                mState.isAccessible = true
                val apState = mState.invoke(wm) as? Int ?: -1
                apState == 13 || apState == 3 // WIFI_AP_STATE_ENABLED (13 on modern, 3 on legacy)
            } catch (_: Throwable) {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Attempts direct SoftAP Hotspot toggle via reflection (works on Android 8.1/head units when allowed),
     * falling back cleanly to Android's native Tethering/Hotspot settings if the OS restricts background toggles.
     */
    @JavascriptInterface
    fun setHotspotEnabled(enabled: Boolean): String {
        val result = JSONObject()
        var directOk = false
        try {
            val wm = wifiManager
            if (wm != null) {
                if (enabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    try {
                        @Suppress("DEPRECATION")
                        if (wm.isWifiEnabled) wm.isWifiEnabled = false
                    } catch (_: Throwable) {}
                }
                val apMethod = wm.javaClass.methods.firstOrNull {
                    it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
                }
                if (apMethod != null) {
                    apMethod.isAccessible = true
                    directOk = (apMethod.invoke(wm, null, enabled) as? Boolean) == true
                }
            }
        } catch (e: Throwable) {
            Log.d("AndroidBridge", "Direct setWifiApEnabled blocked by OS policy: ${e.message}")
            directOk = false
        }

        val currentActive = isHotspotActive()
        if (directOk) {
            result.put("ok", true)
            result.put("active", enabled)
            result.put("openedSettings", false)
            result.put("message", if (enabled) "Zona Wi-Fi activada" else "Zona Wi-Fi desactivada")
            return result.toString()
        }

        val opened = openHotspotSettings()
        result.put("ok", false)
        result.put("active", currentActive)
        result.put("openedSettings", opened)
        result.put("message", "Confirma el interruptor en Ajustes de Zona Wi-Fi de Android")
        return result.toString()
    }

    private fun formatIpv4(ipInt: Int): String {
        if (ipInt == 0) return ""
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            ipInt and 0xff,
            (ipInt shr 8) and 0xff,
            (ipInt shr 16) and 0xff,
            (ipInt shr 24) and 0xff
        )
    }

    /**
     * Returns real connected Wi-Fi SSID, signal RSSI, link speed, local IPv4, internet reachability, and Hotspot state.
     */
    @JavascriptInterface
    fun getWifiStatus(): String {
        return try {
            val enabled = wifiManager?.isWifiEnabled ?: false
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo
            val ssid = if (info != null && info.networkId != -1 && !info.ssid.isNullOrBlank()) {
                val clean = info.ssid.replace("\"", "").trim()
                if (clean == "<unknown ssid>" || clean.isEmpty()) "Wi-Fi Conectado" else clean
            } else {
                "Desconectado"
            }
            @Suppress("DEPRECATION")
            val ipStr = if (info != null && ssid != "Desconectado") formatIpv4(info.ipAddress) else ""
            val rssi = if (info != null && ssid != "Desconectado") info.rssi else -127
            val linkMbps = if (info != null && ssid != "Desconectado") info.linkSpeed else 0

            val json = JSONObject()
            json.put("enabled", enabled)
            json.put("connected", ssid != "Desconectado")
            json.put("ssid", ssid)
            json.put("ip", ipStr)
            json.put("rssi", rssi)
            json.put("linkSpeed", linkMbps)
            json.put("internet", isInternetOnline())
            json.put("hotspot", isHotspotActive())
            json.toString()
        } catch (_: Throwable) {
            "{\"enabled\":false,\"connected\":false,\"ssid\":\"Desconectado\",\"ip\":\"\",\"rssi\":-127,\"linkSpeed\":0,\"internet\":false,\"hotspot\":false}"
        }
    }

    /**
     * Enables or disables Wi-Fi directly on Android <= 9 (AC8227L Oreo) or launches Android Wi-Fi Panel on 10+.
     */
    @JavascriptInterface
    fun setWifiEnabled(enabled: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager?.setWifiEnabled(enabled) ?: false
            } else {
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        } catch (_: Throwable) {
            openWifiSettings()
        }
    }

    private fun isObdName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.contains("OBD", true) || name.contains("ELM", true) ||
            name.contains("VGATE", true) || name.contains("VLINK", true) ||
            name.contains("V-LINK", true) || name.contains("ICAR", true)
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
     * Reads genuine Bluetooth HFP/AT battery level (`getBatteryLevel()` on API 27+/reflection);
     * returns -1 when unavailable instead of inventing a fake 100%.
     */
    private fun getBtDeviceBatteryLevel(dev: BluetoothDevice): Int {
        return try {
            val m = dev.javaClass.getMethod("getBatteryLevel")
            val v = (m.invoke(dev) as? Int) ?: -1
            if (v in 0..100) v else -1
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Returns real active Bluetooth connection state, connected phone vs OBD2 distinction,
     * and genuine HFP battery / cellular signal telemetry (-1 when unavailable).
     */
    @JavascriptInterface
    @SuppressLint("MissingPermission")
    fun getBluetoothStatus(): String {
        return try {
            val adapter = bluetoothAdapter
            val enabled = adapter?.isEnabled ?: false
            if (!enabled || adapter == null) {
                hfpBatteryPct = -1
                hfpSignalBars = -1
                return "{\"enabled\":false,\"connected\":false,\"phoneConnected\":false,\"obdConnected\":false,\"device\":\"Bluetooth apagado\",\"deviceAddress\":\"\",\"phoneBattery\":-1,\"phoneSignal\":-1,\"pairedCount\":0}"
            }

            val bonded = try { adapter.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
            val activeBondedPhone = bonded.firstOrNull { isBtDeviceActivelyConnected(it) && !isObdName(it.name) }
            val activeBondedAny = activeBondedPhone ?: bonded.firstOrNull { isBtDeviceActivelyConnected(it) }

            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val gattConnected = try {
                @Suppress("DEPRECATION")
                bm?.getConnectedDevices(BluetoothProfile.GATT)?.firstOrNull()
            } catch (_: Throwable) {
                null
            }

            val a2dpState = try { adapter.getProfileConnectionState(BluetoothProfile.A2DP) } catch (_: Throwable) { BluetoothProfile.STATE_DISCONNECTED }
            val hfpState = try { adapter.getProfileConnectionState(BluetoothProfile.HEADSET) } catch (_: Throwable) { BluetoothProfile.STATE_DISCONNECTED }
            val a2dpSinkState = try { adapter.getProfileConnectionState(11) } catch (_: Throwable) { BluetoothProfile.STATE_DISCONNECTED }
            val hfpClientState = try { adapter.getProfileConnectionState(16) } catch (_: Throwable) { BluetoothProfile.STATE_DISCONNECTED }
            val audioHfpConnected = a2dpState == BluetoothProfile.STATE_CONNECTED ||
                hfpState == BluetoothProfile.STATE_CONNECTED ||
                a2dpSinkState == BluetoothProfile.STATE_CONNECTED ||
                hfpClientState == BluetoothProfile.STATE_CONNECTED

            val obdConnected = getObdState?.invoke()?.connected == true
            val phoneConnected = activeBondedPhone != null || audioHfpConnected || lastBtPlaying || hfpBatteryPct >= 0
            val isConnected = phoneConnected || obdConnected || gattConnected != null

            if (!phoneConnected) {
                hfpBatteryPct = -1
                hfpSignalBars = -1
            }

            val fallbackPhone = bonded.firstOrNull { !isObdName(it.name) } ?: bonded.firstOrNull()
            val targetPhone = activeBondedPhone ?: (if (audioHfpConnected) fallbackPhone else null)
            val realPhoneBatt = (targetPhone?.let { getBtDeviceBatteryLevel(it) } ?: -1).let {
                if (it >= 0) it else hfpBatteryPct
            }

            val deviceName = activeBondedPhone?.name
                ?: (if (audioHfpConnected) fallbackPhone?.name else null)
                ?: (if (lastBtPlaying) (fallbackPhone?.name ?: "Audio Bluetooth") else null)
                ?: activeBondedAny?.name
                ?: gattConnected?.name
                ?: (if (obdConnected) (getObdState?.invoke()?.deviceName?.ifBlank { "OBD2" } ?: "OBD2") else null)
                ?: (if (bonded.isNotEmpty()) "${bonded.size} vinculado(s)" else "Sin dispositivo")

            val json = JSONObject()
            json.put("enabled", true)
            json.put("connected", isConnected)
            json.put("phoneConnected", phoneConnected)
            json.put("obdConnected", obdConnected)
            json.put("a2dpConnected", a2dpState == BluetoothProfile.STATE_CONNECTED || a2dpSinkState == BluetoothProfile.STATE_CONNECTED)
            json.put("hfpConnected", hfpState == BluetoothProfile.STATE_CONNECTED || hfpClientState == BluetoothProfile.STATE_CONNECTED)
            json.put("device", deviceName)
            json.put("deviceAddress", targetPhone?.address ?: hfpActiveAddress)
            json.put("phoneBattery", realPhoneBatt)
            json.put("phoneSignal", hfpSignalBars)
            json.put("pairedCount", bonded.size)
            json.toString()
        } catch (_: Throwable) {
            "{\"enabled\":false,\"connected\":false,\"phoneConnected\":false,\"obdConnected\":false,\"device\":\"Sin dispositivo\",\"deviceAddress\":\"\",\"phoneBattery\":-1,\"phoneSignal\":-1,\"pairedCount\":0}"
        }
    }

    /**
     * Returns all paired Bluetooth devices as a JSON array with live connection and battery indicator.
     */
    @JavascriptInterface
    @SuppressLint("MissingPermission")
    fun getBluetoothBondedDevices(): String {
        return try {
            val bonded = try { bluetoothAdapter?.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
            val arr = JSONArray()
            for (dev in bonded) {
                val obj = JSONObject()
                val name = dev.name ?: "Dispositivo Bluetooth"
                val active = isBtDeviceActivelyConnected(dev)
                val batt = getBtDeviceBatteryLevel(dev)
                obj.put("name", name)
                obj.put("address", dev.address ?: "")
                obj.put("connected", active)
                obj.put("battery", if (batt >= 0) batt else if (active && !isObdName(name)) hfpBatteryPct else -1)
                obj.put("type", if (isObdName(name)) "obd" else "phone")
                arr.put(obj)
            }
            arr.toString()
        } catch (_: Throwable) {
            "[]"
        }
    }

    /**
     * Initiates real Bluetooth A2DP/HFP profile connection to a paired device; falls back cleanly
     * to native Bluetooth Settings if OEM stack/Android refuses direct proxy connection.
     */
    @JavascriptInterface
    fun connectBluetoothDevice(address: String): String {
        val result = JSONObject()
        val cleanAddr = address.trim()
        val ok = try {
            if (cleanAddr.isNotEmpty()) onConnectBluetoothDevice?.invoke(cleanAddr) == true else false
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            result.put("ok", true)
            result.put("address", cleanAddr)
            result.put("openedSettings", false)
            return result.toString()
        }
        val opened = openBluetoothSettings()
        result.put("ok", false)
        result.put("address", cleanAddr)
        result.put("openedSettings", opened)
        return result.toString()
    }

    @JavascriptInterface
    fun disconnectBluetoothDevice(address: String): String {
        val result = JSONObject()
        val cleanAddr = address.trim()
        val ok = try {
            if (cleanAddr.isNotEmpty()) onDisconnectBluetoothDevice?.invoke(cleanAddr) == true else false
        } catch (_: Throwable) {
            false
        }
        result.put("ok", ok)
        result.put("address", cleanAddr)
        return result.toString()
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
     * Dispatches real hardware media keys (Play/Pause, Next, Prev) to active MediaSession or system.
     */
    @JavascriptInterface
    fun sendMediaKeyEvent(action: String) {
        try {
            val handledBySession = try {
                onMediaTransportAction?.invoke(action.lowercase()) == true
            } catch (_: Throwable) {
                false
            }
            if (handledBySession) return

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
     * Seeks playback position (seconds) on the active MediaSession controller if supported.
     */
    @JavascriptInterface
    fun seekActiveMedia(positionSec: Int): Boolean {
        return try {
            val posMs = positionSec.coerceAtLeast(0).toLong() * 1000L
            onSeekMediaPosition?.invoke(posMs) ?: false
        } catch (_: Throwable) {
            false
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
     * Checks if JoyOS has Android permission to modify global system settings (brightness, sleep timeout).
     */
    @JavascriptInterface
    fun canWriteSystemSettings(): Boolean {
        return try {
            Settings.System.canWrite(context)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Opens Android's "Modify system settings" permission toggle directly for JoyOS.
     */
    @JavascriptInterface
    fun openWriteSettingsPermission(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            openAndroidSettings()
        }
    }

    /**
     * Applies physical screen brightness (10 to 100 scale) cleanly on Window and System Settings.
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
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    (clamped * 255) / 100
                )
            }
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Screen brightness handled: ${e.message}")
        }
    }

    /**
     * Toggles hardware Bluetooth state.
     * On Android 13+ (API 33+), direct enable()/disable() is blocked for non-system apps,
     * so we gracefully prompt the system Bluetooth enable dialog or settings screen.
     */
    @JavascriptInterface
    fun setBluetoothPower(enabled: Boolean): Boolean {
        return try {
            val adapter = bluetoothAdapter ?: run {
                openBluetoothSettings()
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                openBluetoothSettings()
                return false
            }
            val toggled = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                try {
                    @Suppress("DEPRECATION")
                    if (enabled) adapter.enable() else adapter.disable()
                } catch (_: SecurityException) {
                    false
                }
            } else {
                false
            }
            if (toggled) {
                true
            } else {
                if (enabled) {
                    try {
                        val reqIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(reqIntent)
                    } catch (_: Throwable) {
                        openBluetoothSettings()
                    }
                } else {
                    openBluetoothSettings()
                }
                false
            }
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Bluetooth power toggle handled: ${e.message}")
            openBluetoothSettings()
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
     * Returns real-time GPS location, bearing, speed, accuracy, provider, and fix state.
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
                put("provider", data.provider)
                put("accuracy", data.accuracyM.toDouble())
            }
            json.toString()
        } catch (_: Throwable) {
            "{\"lat\":4.6534,\"lon\":-74.0836,\"latitude\":4.6534,\"longitude\":-74.0836,\"speed\":0,\"bearing\":0,\"altitude\":2600,\"hasFix\":false,\"tripKm\":0,\"provider\":\"standby\",\"accuracy\":-1}"
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
            for ((idx, file) in foundAudio.withIndex()) {
                var title = file.nameWithoutExtension
                var artist = "Desconocido"
                var album = if (file.absolutePath.contains("usb") || file.absolutePath.contains("mnt")) "Memoria USB" else "Almacenamiento Local"
                var durationSec = 210
                var coverDataUri = ""
                var color1 = ""
                var color2 = ""
                var hasEmbeddedArt = false

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

                    val pic = try { mmr.embeddedPicture } catch (_: Throwable) { null }
                    if (pic != null && pic.isNotEmpty()) {
                        hasEmbeddedArt = true
                        // Inline data URI for the first 20 tracks; larger libraries lazy-load via getTrackArtwork()
                        if (idx < 20) {
                            val artRes = JoyMediaArtworkHelper.processBytesToArtwork(pic, maxDim = 220, quality = 76)
                            if (artRes != null) {
                                coverDataUri = artRes.dataUri
                                color1 = artRes.color1
                                color2 = artRes.color2
                            }
                        }
                    }
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
                    put("hasEmbeddedArt", hasEmbeddedArt)
                    if (coverDataUri.isNotEmpty()) put("coverDataUri", coverDataUri)
                    if (color1.isNotEmpty()) put("color1", color1)
                    if (color2.isNotEmpty()) put("color2", color2)
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

    /**
     * Lazy artwork extraction for local/USB audio tracks (keeps initial getMusicFiles() JSON compact).
     */
    @JavascriptInterface
    fun getTrackArtwork(filePath: String): String {
        if (filePath.isBlank()) return "{\"ok\":false}"
        val mmr = MediaMetadataRetriever()
        return try {
            val f = File(filePath)
            if (!f.exists() || !f.canRead()) return "{\"ok\":false}"
            mmr.setDataSource(f.absolutePath)
            val pic = mmr.embeddedPicture ?: return "{\"ok\":false}"
            val res = JoyMediaArtworkHelper.processBytesToArtwork(pic, maxDim = 260, quality = 78)
                ?: return "{\"ok\":false}"
            JSONObject().apply {
                put("ok", true)
                put("coverDataUri", res.dataUri)
                put("color1", res.color1)
                put("color2", res.color2)
            }.toString()
        } catch (_: Throwable) {
            "{\"ok\":false}"
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    // --- LIVE BLUETOOTH AVRCP / MEDIA SESSION TRACKING ---

    private val btMetaLock = Any()
    private val mediaBridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var artworkLookupJob: Job? = null

    @Volatile private var lastBtTitle: String = ""
    @Volatile private var lastBtArtist: String = ""
    @Volatile private var lastBtAlbum: String = ""
    @Volatile private var lastBtDurationSec: Int = 210
    @Volatile private var lastBtPositionSec: Int = -1
    @Volatile private var lastBtPlaying: Boolean = false
    @Volatile private var lastBtActionSource: String = ""
    @Volatile private var lastBtSourceApp: String = ""
    @Volatile private var lastBtCoverDataUri: String = ""
    @Volatile private var lastBtColor1: String = ""
    @Volatile private var lastBtColor2: String = ""

    private fun buildBtMetadataJson(): JSONObject {
        return JSONObject().apply {
            put("title", lastBtTitle)
            put("artist", if (lastBtArtist.isNotBlank()) lastBtArtist else "Bluetooth Audio")
            put("album", if (lastBtAlbum.isNotBlank()) lastBtAlbum else "A2DP Stream")
            put("durationSec", if (lastBtDurationSec > 0) lastBtDurationSec else 210)
            put("positionSec", lastBtPositionSec)
            put("playing", lastBtPlaying)
            put("source", lastBtActionSource)
            put("sourceApp", lastBtSourceApp)
            put("hasNotificationAccess", JoyMediaNotificationListener.isEnabled(context))
            if (lastBtCoverDataUri.isNotEmpty()) put("coverDataUri", lastBtCoverDataUri)
            if (lastBtColor1.isNotEmpty()) put("color1", lastBtColor1)
            if (lastBtColor2.isNotEmpty()) put("color2", lastBtColor2)
        }
    }

    fun updateLiveBtMetadata(
        title: String?,
        artist: String?,
        album: String?,
        durationSec: Int,
        isPlaying: Boolean?,
        sourceAction: String,
        positionSec: Int = -1,
        coverDataUri: String? = null,
        color1: String? = null,
        color2: String? = null,
        sourceApp: String? = null,
        artUrlHint: String? = null
    ) {
        var needOnlineLookup = false
        var queryTitle = ""
        var queryArtist = ""
        val json: JSONObject

        synchronized(btMetaLock) {
            val nextTitle = title?.trim().orEmpty()
            val nextArtist = artist?.trim().orEmpty()
            val trackChanged = nextTitle.isNotEmpty() && (
                !nextTitle.equals(lastBtTitle, ignoreCase = true) ||
                    (nextArtist.isNotEmpty() && !nextArtist.equals(lastBtArtist, ignoreCase = true))
                )

            if (trackChanged) {
                lastBtCoverDataUri = ""
                lastBtColor1 = ""
                lastBtColor2 = ""
                if (positionSec < 0) lastBtPositionSec = 0
            }

            if (nextTitle.isNotEmpty()) lastBtTitle = nextTitle
            if (nextArtist.isNotEmpty()) lastBtArtist = nextArtist
            if (!album.isNullOrBlank()) lastBtAlbum = album.trim()
            if (durationSec > 0) lastBtDurationSec = durationSec
            if (positionSec >= 0) lastBtPositionSec = positionSec
            if (isPlaying != null) lastBtPlaying = isPlaying
            if (sourceAction.isNotBlank()) lastBtActionSource = sourceAction
            if (!sourceApp.isNullOrBlank()) lastBtSourceApp = sourceApp

            if (!coverDataUri.isNullOrBlank()) {
                lastBtCoverDataUri = coverDataUri
                if (!color1.isNullOrBlank()) lastBtColor1 = color1
                if (!color2.isNullOrBlank()) lastBtColor2 = color2
            } else if (lastBtCoverDataUri.isEmpty() && lastBtTitle.isNotBlank()) {
                needOnlineLookup = true
                queryTitle = lastBtTitle
                queryArtist = lastBtArtist
            }

            json = buildBtMetadataJson()
        }

        onEvaluateJs?.invoke("if (typeof window.onBtMusicMetadata === 'function') window.onBtMusicMetadata($json);")

        if (needOnlineLookup) {
            artworkLookupJob?.cancel()
            artworkLookupJob = mediaBridgeScope.launch {
                val artRes = JoyMediaArtworkHelper.fetchArtworkForTrack(queryTitle, queryArtist, artUrlHint)
                if (artRes != null) {
                    var updatedJson: JSONObject? = null
                    synchronized(btMetaLock) {
                        if (lastBtTitle.equals(queryTitle, ignoreCase = true) && lastBtCoverDataUri.isEmpty()) {
                            lastBtCoverDataUri = artRes.dataUri
                            lastBtColor1 = artRes.color1
                            lastBtColor2 = artRes.color2
                            updatedJson = buildBtMetadataJson()
                        }
                    }
                    updatedJson?.let { payload ->
                        onEvaluateJs?.invoke("if (typeof window.onBtMusicMetadata === 'function') window.onBtMusicMetadata($payload);")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun getBtMusicMetadata(): String {
        return try {
            synchronized(btMetaLock) {
                buildBtMetadataJson().toString()
            }
        } catch (_: Throwable) {
            "{}"
        }
    }

    // ─── Hands-Free Calls (HFP Client, Telephony & NotificationListener) ───────────────────────

    private val callLock = Any()
    private var callState: String = "idle"
    private var callName: String = ""
    private var callNumber: String = ""
    private var callAvatar: String = ""
    private var callSource: String = ""
    private var callStartedAtMs: Long = 0L
    private var callMuted: Boolean = false

    fun updateCallState(
        state: String,
        name: String? = null,
        number: String? = null,
        avatar: String? = null,
        source: String = "",
        elapsedSecHint: Long = -1L
    ) {
        val normalized = when (state.lowercase(Locale.ROOT)) {
            "ringing", "incoming", "waiting" -> "ringing"
            "dialing", "alerting", "outgoing" -> "dialing"
            "active", "offhook", "connected", "held" -> "active"
            else -> "idle"
        }

        val payload = synchronized(callLock) {
            if (normalized == "idle") {
                // Only ignore a notification removal if an active HFP/Telephony call owns the state
                if (source == "notification" && callSource.isNotEmpty() && callSource != "notification" && callState != "idle") {
                    return@synchronized null
                }
                if (callState == "idle" && callName.isEmpty() && callNumber.isEmpty()) {
                    return@synchronized null
                }
                callState = "idle"
                callName = ""
                callNumber = ""
                callAvatar = ""
                callSource = ""
                callStartedAtMs = 0L
                callMuted = false
            } else {
                val wasIdle = callState == "idle"
                val cleanName = name?.trim().orEmpty()
                val cleanNum = number?.trim().orEmpty()
                val cleanAvatar = avatar?.trim().orEmpty()

                if (wasIdle) {
                    callName = cleanName
                    callNumber = cleanNum
                    callAvatar = cleanAvatar
                    callStartedAtMs = if (normalized == "active") {
                        if (elapsedSecHint >= 0L) System.currentTimeMillis() - (elapsedSecHint * 1000L) else System.currentTimeMillis()
                    } else 0L
                    callMuted = audioManager?.isMicrophoneMute ?: false
                } else {
                    if (cleanName.isNotEmpty()) callName = cleanName
                    if (cleanNum.isNotEmpty()) callNumber = cleanNum
                    if (cleanAvatar.isNotEmpty()) callAvatar = cleanAvatar
                    if (normalized == "active" && callStartedAtMs == 0L) {
                        callStartedAtMs = if (elapsedSecHint >= 0L) {
                            System.currentTimeMillis() - (elapsedSecHint * 1000L)
                        } else {
                            System.currentTimeMillis()
                        }
                    }
                }
                callState = normalized
                if (source.isNotEmpty() && (wasIdle || source == "hfp" || callSource.isEmpty())) {
                    callSource = source
                }
            }
            buildCallStatusJson().toString()
        } ?: return

        onEvaluateJs?.invoke("if (typeof window.onCallStateChanged === 'function') window.onCallStateChanged($payload);")
    }

    private fun buildCallStatusJson(): JSONObject {
        val now = System.currentTimeMillis()
        val durationSec = if (callState == "active" && callStartedAtMs > 0L) {
            ((now - callStartedAtMs) / 1000L).coerceAtLeast(0L)
        } else 0L
        val realMuted = try {
            audioManager?.isMicrophoneMute ?: callMuted
        } catch (_: Throwable) {
            callMuted
        }
        return JSONObject().apply {
            put("state", callState)
            put("active", callState != "idle")
            put("name", callName)
            put("number", callNumber)
            put("avatar", callAvatar)
            put("source", callSource)
            put("startedAt", callStartedAtMs)
            put("durationSec", durationSec)
            put("muted", realMuted)
        }
    }

    @JavascriptInterface
    fun getCallStatus(): String {
        return try {
            synchronized(callLock) {
                buildCallStatusJson().toString()
            }
        } catch (_: Throwable) {
            "{\"state\":\"idle\",\"active\":false,\"name\":\"\",\"number\":\"\",\"avatar\":\"\",\"source\":\"\",\"startedAt\":0,\"durationSec\":0,\"muted\":false}"
        }
    }

    @JavascriptInterface
    @SuppressLint("MissingPermission")
    fun answerActiveCall(): Boolean {
        return try {
            if (onAnswerCallAction?.invoke() == true) return true
            if (JoyMediaNotificationListener.invokeAnswerAction()) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
            ) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION")
                tm?.acceptRingingCall()
                return tm != null
            }
            false
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "answerActiveCall handled: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    @SuppressLint("MissingPermission")
    fun endActiveCall(): Boolean {
        return try {
            if (onEndCallAction?.invoke() == true) return true
            if (JoyMediaNotificationListener.invokeDeclineAction()) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
            ) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION")
                if (tm?.endCall() == true) return true
            }
            false
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "endActiveCall handled: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun toggleCallMute(): Boolean {
        return try {
            val current = audioManager?.isMicrophoneMute ?: false
            val target = !current
            audioManager?.isMicrophoneMute = target
            val actual = audioManager?.isMicrophoneMute ?: target
            val payload = synchronized(callLock) {
                callMuted = actual
                buildCallStatusJson().toString()
            }
            onEvaluateJs?.invoke("if (typeof window.onCallStateChanged === 'function') window.onCallStateChanged($payload);")
            actual
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "toggleCallMute handled: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun dialPhoneNumber(rawNumber: String): String {
        val clean = rawNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' || it == ';' }
        if (clean.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("direct", false)
                put("number", "")
                put("message", "Ingresa un número válido para marcar")
            }.toString()
        }
        if (onDialCallAction?.invoke(clean) == true) {
            return JSONObject().apply {
                put("ok", true)
                put("direct", true)
                put("number", clean)
                put("message", "Marcando vía Bluetooth manos libres a $clean...")
            }.toString()
        }
        val telUri = Uri.fromParts("tel", clean, null)
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (hasCallPerm) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL, telUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (callIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(callIntent)
                    return JSONObject().apply {
                        put("ok", true)
                        put("direct", true)
                        put("number", clean)
                        put("message", "Llamando a $clean...")
                    }.toString()
                }
            } catch (_: Throwable) {
                // Fallback to ACTION_DIAL below
            }
        }
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, telUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (dialIntent.resolveActivity(packageManager) != null) {
                context.startActivity(dialIntent)
                JSONObject().apply {
                    put("ok", true)
                    put("direct", false)
                    put("number", clean)
                    put("message", "Abriendo marcador para $clean...")
                }.toString()
            } else {
                JSONObject().apply {
                    put("ok", false)
                    put("direct", false)
                    put("number", clean)
                    put("message", "Esta unidad no expone marcador telefónico nativo")
                }.toString()
            }
        } catch (e: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("direct", false)
                put("number", clean)
                put("message", "No se pudo iniciar la llamada: ${e.message ?: "error"}")
            }.toString()
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

    @JavascriptInterface
    fun setGpsSourceMode(mode: String) {
        onSetGpsSource?.invoke(mode)
    }

    @JavascriptInterface
    fun resetTripOdometer() {
        onResetTrip?.invoke()
    }

    @JavascriptInterface
    fun setScreenTimeoutMode(mode: String) {
        onSetScreenTimeout?.invoke(mode)
    }

    @JavascriptInterface
    fun setOtaAutoCheck(enabled: Boolean) {
        try {
            context.getSharedPreferences("joyos_system_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("ota_auto_wifi", enabled)
                .apply()
        } catch (_: Throwable) {}
    }

    @JavascriptInterface
    fun isOtaAutoCheckEnabled(): Boolean {
        return try {
            context.getSharedPreferences("joyos_system_prefs", Context.MODE_PRIVATE)
                .getBoolean("ota_auto_wifi", true)
        } catch (_: Throwable) {
            true
        }
    }

    @JavascriptInterface
    fun setCoolingFanMode(mode: String, thresholdC: Int, continuous: Boolean): String {
        val cleanMode = when (mode.lowercase()) {
            "turbo" -> "turbo"
            "silent", "silencio" -> "silent"
            else -> "auto"
        }
        val cpuTemp = getCpuTemperature()
        var hwDispatched = false
        try {
            context.getSharedPreferences("joyos_thermal_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("fan_mode", cleanMode)
                .putInt("fan_threshold_c", thresholdC.coerceIn(45, 95))
                .putBoolean("fan_continuous", continuous)
                .apply()

            val shouldBoost = cleanMode == "turbo" || continuous || (cpuTemp > 0 && cpuTemp >= thresholdC)
            val fanValue = when {
                cleanMode == "turbo" -> "255"
                cleanMode == "silent" && (cpuTemp <= 0 || cpuTemp < thresholdC) -> "64"
                shouldBoost -> "180"
                else -> "120"
            }

            val fanNodes = listOf(
                "/sys/class/thermal/cooling_device0/cur_state",
                "/sys/class/hwmon/hwmon0/pwm1",
                "/sys/devices/platform/pwm-fan/hwmon/hwmon0/pwm1"
            )
            for (nodePath in fanNodes) {
                val f = File(nodePath)
                if (f.exists() && f.canWrite()) {
                    try {
                        f.writeText(if (nodePath.endsWith("cur_state")) (if (shouldBoost) "1" else "0") else fanValue)
                        hwDispatched = true
                    } catch (_: Throwable) {}
                }
            }

            listOf(
                Intent("com.ts.main.ACTION_FAN_CONTROL").putExtra("mode", cleanMode).putExtra("active", shouldBoost),
                Intent("xyauto.intent.action.FAN_CONTROL").putExtra("mode", cleanMode).putExtra("threshold", thresholdC),
                Intent("com.autochips.fan.CONTROL").putExtra("enable", shouldBoost)
            ).forEach { intent ->
                try { context.sendBroadcast(intent) } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.w("AndroidBridge", "Cooling fan control handled: ${e.message}")
        }
        return JSONObject().apply {
            put("mode", cleanMode)
            put("thresholdC", thresholdC)
            put("continuous", continuous)
            put("cpuTempC", cpuTemp)
            put("hwDispatched", hwDispatched)
        }.toString()
    }

    @JavascriptInterface
    fun setIgnitionOffDelay(mode: String): Boolean {
        return try {
            val seconds = when (mode.lowercase()) {
                "5m", "5 min" -> 300
                "never", "nunca" -> -1
                else -> 60
            }
            context.getSharedPreferences("joyos_system_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("ignition_off_delay", mode)
                .apply()
            listOf(
                Intent("xyauto.intent.action.ACC_OFF_DELAY").putExtra("delay_sec", seconds),
                Intent("com.ts.main.ACTION_ACC_DELAY").putExtra("delay_sec", seconds)
            ).forEach { intent ->
                try { context.sendBroadcast(intent) } catch (_: Throwable) {}
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun openHotspotSettings(): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    @JavascriptInterface
    fun openLocationSettings(): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) { openAndroidSettings() }
    }

    @JavascriptInterface
    fun openDisplaySettings(): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) { openAndroidSettings() }
    }

    @JavascriptInterface
    fun openSoundSettings(): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) { openAndroidSettings() }
    }

    @JavascriptInterface
    fun openDateTimeSettings(): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) { openAndroidSettings() }
    }

    @JavascriptInterface
    fun openAppSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Throwable) { openAndroidSettings() }
    }

    @JavascriptInterface
    fun openInstallUnknownAppsPermission(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Throwable) { openAppSettings() }
    }

    @JavascriptInterface
    fun openStoragePermissionSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (_: Throwable) { openAppSettings() }
        } else {
            openAppSettings()
        }
    }

    @JavascriptInterface
    fun openNotificationListenerSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Throwable) { openAppSettings() }
    }

    @JavascriptInterface
    fun isZlinkInstalled(): Boolean {
        val candidates = listOf("com.zjinnova.zlink", "com.zlink.app", "com.carplay.zlink", "com.tlink.app", "gb.xxy.hr")
        for (pkg in candidates) {
            if (packageManager.getLaunchIntentForPackage(pkg) != null) return true
        }
        return try {
            val pkgs = packageManager.getInstalledPackages(0)
            pkgs.any { p ->
                val l = p.packageName.lowercase()
                l.contains("zlink") || l.contains("tlink") || l.contains("carplay")
            }
        } catch (_: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun getSystemPermissionsStatus(): String {
        return try {
            val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else true
            val storageGranted = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager() ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)
                else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            val writeSettings = try { Settings.System.canWrite(context) } catch (_: Throwable) { false }
            val installUnknown = try { packageManager.canRequestPackageInstalls() } catch (_: Throwable) { false }
            val notifListener = JoyMediaNotificationListener.isEnabled(context)
            val phoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED)
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val defaultHomePkg = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))?.activityInfo?.packageName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                }
            } catch (_: Throwable) { null }
            val isDefaultLauncher = defaultHomePkg == context.packageName

            JSONObject().apply {
                put("location", locGranted)
                put("bluetooth", btGranted)
                put("storage", storageGranted)
                put("writeSettings", writeSettings)
                put("installPackages", installUnknown)
                put("notificationListener", notifListener)
                put("phone", phoneGranted)
                put("isDefaultLauncher", isDefaultLauncher)
                val count = listOf(locGranted, btGranted, storageGranted, writeSettings, installUnknown, notifListener, phoneGranted).count { it }
                put("grantedCount", count)
                put("totalCount", 7)
            }.toString()
        } catch (_: Throwable) {
            "{\"location\":false,\"bluetooth\":false,\"storage\":false,\"writeSettings\":false,\"installPackages\":false,\"notificationListener\":false,\"phone\":false,\"isDefaultLauncher\":false,\"grantedCount\":0,\"totalCount\":7}"
        }
    }

    @JavascriptInterface
    fun requestMissingPermissions(): Boolean {
        return try {
            onRequestPermissions?.invoke()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun deleteDirContents(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var bytes = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                bytes += deleteDirContents(f)
                try { f.delete() } catch (_: Throwable) {}
            } else {
                val len = f.length()
                if (try { f.delete() } catch (_: Throwable) { false }) {
                    bytes += len
                }
            }
        }
        return bytes
    }

    @JavascriptInterface
    fun clearAppCache(): String {
        return try {
            onClearWebCache?.invoke()
            var freedBytes = 0L
            freedBytes += deleteDirContents(context.cacheDir)
            freedBytes += deleteDirContents(context.codeCacheDir)
            freedBytes += deleteDirContents(context.externalCacheDir)
            val kb = freedBytes / 1024L
            JSONObject().apply {
                put("freedKb", kb)
                put("clearedKb", kb)
                put("ok", true)
            }.toString()
        } catch (e: Throwable) {
            "{\"freedKb\":0,\"clearedKb\":0,\"ok\":false,\"error\":\"${e.message}\"}"
        }
    }

    @JavascriptInterface
    fun exportBackupJson(jsonPayload: String): String {
        return try {
            val targets = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                context.filesDir
            )
            var savedPath = ""
            for (dir in targets) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    if (dir.canWrite()) {
                        val out = File(dir, "JoyOS_Backup.json")
                        out.writeText(jsonPayload, Charsets.UTF_8)
                        savedPath = out.absolutePath
                        break
                    }
                } catch (_: Throwable) {}
            }
            JSONObject().apply {
                put("ok", savedPath.isNotEmpty())
                put("path", savedPath)
            }.toString()
        } catch (e: Throwable) {
            "{\"ok\":false,\"path\":\"\",\"error\":\"${e.message}\"}"
        }
    }

    @JavascriptInterface
    fun importBackupJson(): String {
        return try {
            val candidates = listOfNotNull(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JoyOS_Backup.json"),
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "JoyOS_Backup.json") },
                File(context.filesDir, "JoyOS_Backup.json")
            )
            for (f in candidates) {
                if (f.exists() && f.canRead() && f.length() > 10) {
                    val raw = f.readText(Charsets.UTF_8)
                    return JSONObject().apply {
                        put("ok", true)
                        put("path", f.absolutePath)
                        put("json", raw)
                    }.toString()
                }
            }
            "{\"ok\":false,\"path\":\"\",\"json\":\"\"}"
        } catch (_: Throwable) {
            "{\"ok\":false,\"path\":\"\",\"json\":\"\"}"
        }
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
