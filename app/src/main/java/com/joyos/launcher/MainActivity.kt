package com.joyos.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.joyos.launcher.bridge.AndroidBridge
import com.joyos.launcher.services.GpsTelemetryManager
import com.joyos.launcher.services.JoyAudioDspEngine
import com.joyos.launcher.services.JoyHardwareOptimizer
import com.joyos.launcher.services.JoyMediaArtworkHelper
import com.joyos.launcher.services.JoyMediaNotificationListener
import com.joyos.launcher.services.ObdBleManager
import com.joyos.launcher.services.OtaUpdateManager
import com.joyos.launcher.services.TpmsUsbReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private lateinit var dspEngine: JoyAudioDspEngine
    private lateinit var gpsManager: GpsTelemetryManager
    private lateinit var obdManager: ObdBleManager
    private lateinit var tpmsReceiver: TpmsUsbReceiver
    private lateinit var otaManager: OtaUpdateManager
    private lateinit var hardwareOptimizer: JoyHardwareOptimizer

    @Volatile
    private var hotTempThresholdC: Int = 104

    @Volatile
    private var screenTimeoutMode: String = "never"

    private var mediaSessionManager: MediaSessionManager? = null
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var activeControllers: List<MediaController> = emptyList()
    private var sessionsChangedListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    @Volatile private var lastSessionTrackKey: String = ""
    @Volatile private var lastSessionArt: JoyMediaArtworkHelper.ArtworkResult? = null
    @Volatile private var lastSessionArtHint: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install uncaught exception guard to capture and prevent fatal launch crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("JoyOS", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            val sysPrefs = getSharedPreferences("joyos_system_prefs", Context.MODE_PRIVATE)
            screenTimeoutMode = sysPrefs.getString("screen_timeout_mode", "never") ?: "never"
        } catch (_: Throwable) {}

        try {
            hideSystemUI()
            applyScreenTimeoutMode(screenTimeoutMode)
        } catch (e: Throwable) {
            Log.w("JoyOS", "System UI hide warning: ${e.message}")
        }

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        dspEngine = JoyAudioDspEngine(this)
        otaManager = OtaUpdateManager(this)
        hardwareOptimizer = JoyHardwareOptimizer(this)

        gpsManager = GpsTelemetryManager(this) { data ->
            try {
                dspEngine.onSpeedUpdated(data.speedKmh)
            } catch (_: Throwable) {}
            val tripFormatted = String.format(Locale.US, "%.1f", data.tripKm)
            val latFormatted = String.format(Locale.US, "%.6f", data.latitude)
            val lonFormatted = String.format(Locale.US, "%.6f", data.longitude)
            val bearFormatted = String.format(Locale.US, "%.1f", data.bearing)
            val altFormatted = String.format(Locale.US, "%.1f", data.altitude)
            val accFormatted = String.format(Locale.US, "%.0f", data.accuracyM)
            val providerQuoted = JSONObject.quote(data.provider)
            runOnUiThread {
                try {
                    webView.evaluateJavascript(
                        "if (typeof window.onGpsUpdate === 'function') window.onGpsUpdate({ speed: ${data.speedKmh}, tripKm: $tripFormatted, lat: $latFormatted, lon: $lonFormatted, bearing: $bearFormatted, altitude: $altFormatted, hasFix: ${data.hasFix}, provider: $providerQuoted, accuracy: $accFormatted });",
                        null
                    )
                    webView.evaluateJavascript(
                        "if (typeof window.onTelemetryUpdate === 'function') window.onTelemetryUpdate({ speed: ${data.speedKmh}, tripKm: $tripFormatted });",
                        null
                    )
                } catch (_: Throwable) {}
            }
        }

        bridge = AndroidBridge(
            context = this,
            dspEngine = dspEngine,
            otaManager = otaManager,
            hardwareOptimizer = hardwareOptimizer,
            getGpsData = { gpsManager.currentGpsData },
            getObdState = { obdManager.currentState },
            getTpmsState = { tpmsReceiver.currentState },
            onSetAlertThresholds = { lowPsi, hotTempC ->
                hotTempThresholdC = hotTempC.coerceIn(90, 125)
                tpmsReceiver.updateThresholds(lowPsi, 75)
            },
            onReconnectHardware = {
                try { obdManager.triggerReconnect() } catch (_: Throwable) {}
                try { tpmsReceiver.triggerReprobe() } catch (_: Throwable) {}
            },
            onSetGpsSource = { mode -> gpsManager.setSourceMode(mode) },
            onResetTrip = { gpsManager.resetTripOdometer() },
            onSetScreenTimeout = { mode -> runOnUiThread { applyScreenTimeoutMode(mode) } },
            onRequestPermissions = { runOnUiThread { ensureAutomotivePermissions() } },
            onClearWebCache = { runOnUiThread { try { webView.clearCache(true) } catch (_: Throwable) {} } },
            onReloadUi = { runOnUiThread { loadJoyOS() } },
            onEvaluateJs = { script -> runOnUiThread { webView.evaluateJavascript(script, null) } },
            onMediaTransportAction = { action -> handleSessionTransportAction(action) },
            onSeekMediaPosition = { posMs -> seekSessionPosition(posMs) },
            onConnectBluetoothDevice = { address -> connectBtDeviceProxy(address) },
            onDisconnectBluetoothDevice = { address -> disconnectBtDeviceProxy(address) },
            onAnswerCallAction = { answerBtOrTelecomCall() },
            onEndCallAction = { endBtOrTelecomCall() },
            onDialCallAction = { number -> dialBtHfpCall(number) }
        )

        obdManager = ObdBleManager(this) { obd ->
            try {
                if (!gpsManager.currentGpsData.hasFix && obd.connected && obd.speedKmh > 0) {
                    dspEngine.onSpeedUpdated(obd.speedKmh)
                }
            } catch (_: Throwable) {}
            val hot = obd.coolantTempC > -90 && obd.coolantTempC >= hotTempThresholdC
            val voltJson = if (obd.batteryVoltage > 5.0) String.format(Locale.US, "%.1f", obd.batteryVoltage) else "null"
            val consJson = if (obd.consumptionKmL > 0.5) String.format(Locale.US, "%.1f", obd.consumptionKmL) else "null"
            val payload = JSONObject().apply {
                put("rpm", obd.rpm)
                put("coolantTemp", if (obd.coolantTempC > -90) obd.coolantTempC else JSONObject.NULL)
                put("engineHot", hot)
                put("obdSpeed", obd.speedKmh)
                put("loadPct", if (obd.engineLoadPct >= 0) obd.engineLoadPct else JSONObject.NULL)
                put("obdConnected", obd.connected)
                put("obdTransport", obd.transport)
                put("obdDevice", obd.deviceName)
            }
            runOnUiThread {
                try {
                    val script = "if (typeof window.onTelemetryUpdate === 'function') { var _d = $payload; _d.voltage = $voltJson; _d.consumptionKmL = $consJson; window.onTelemetryUpdate(_d); }"
                    webView.evaluateJavascript(script, null)
                } catch (_: Throwable) {}
            }
        }

        tpmsReceiver = TpmsUsbReceiver(this) { tpms ->
            val payload = JSONObject().apply {
                put("tireAlert", tpms.lowAlert)
                put("tpmsConnected", tpms.connected)
                put("tpmsSource", tpms.source)
                put("tpmsDevice", tpms.deviceLabel)
                put("psiFl", if (tpms.flPsi >= 0) tpms.flPsi else JSONObject.NULL)
                put("psiFr", if (tpms.frPsi >= 0) tpms.frPsi else JSONObject.NULL)
                put("psiRl", if (tpms.rlPsi >= 0) tpms.rlPsi else JSONObject.NULL)
                put("psiRr", if (tpms.rrPsi >= 0) tpms.rrPsi else JSONObject.NULL)
                put("tempFl", if (tpms.flTempC > -90) tpms.flTempC else JSONObject.NULL)
                put("tempFr", if (tpms.frTempC > -90) tpms.frTempC else JSONObject.NULL)
                put("tempRl", if (tpms.rlTempC > -90) tpms.rlTempC else JSONObject.NULL)
                put("tempRr", if (tpms.rrTempC > -90) tpms.rrTempC else JSONObject.NULL)
            }
            runOnUiThread {
                try {
                    webView.evaluateJavascript(
                        "if (typeof window.onTelemetryUpdate === 'function') window.onTelemetryUpdate($payload);",
                        null
                    )
                } catch (_: Throwable) {}
            }
        }
        tpmsReceiver.handleUsbAttachIntent(intent)

        configureWebView()
        loadJoyOS()
        ensureAutomotivePermissions()
        registerAutomotiveMediaReceivers()
        registerConnectivityAndCallReceivers()
        initBluetoothProfiles()
        JoyMediaNotificationListener.setOnListenerReadyCallback {
            runOnUiThread { attachMediaSessionsIfAllowed() }
        }
        JoyMediaNotificationListener.setOnCallNotificationCallback { callInfo ->
            if (callInfo == null) {
                bridge.updateCallState("idle", source = "notification")
            } else {
                bridge.updateCallState(
                    state = callInfo.status,
                    name = callInfo.callerName,
                    number = callInfo.callerNumber,
                    avatar = callInfo.photoDataUri,
                    source = "notification"
                )
            }
        }
        attachMediaSessionsIfAllowed()
        scheduleBackgroundOtaCheck()

        // Handle back button: as a Home Launcher, navigate back to home view rather than exiting
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    webView.evaluateJavascript("if (typeof switchTab === 'function') switchTab('home');", null)
                } catch (_: Throwable) {}
            }
        })
    }

    private val btMediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            try {
                val action = intent.action ?: ""
                val extras = intent.extras
                var title: String? = null
                var artist: String? = null
                var album: String? = null
                var durationMs: Long = 0L
                var isPlaying: Boolean? = null

                if (extras != null) {
                    val titleKeys = listOf("track", "title", "music_title", "song_name", "bt_music_title", "android.intent.extra.TITLE", "media_title")
                    val artistKeys = listOf("artist", "music_artist", "singer_name", "bt_music_artist", "android.intent.extra.ARTIST", "media_artist")
                    val albumKeys = listOf("album", "music_album", "album_name", "bt_music_album", "android.intent.extra.ALBUM", "media_album")
                    val durKeys = listOf("duration", "total_time", "music_duration", "time_total")
                    val playKeys = listOf("playing", "isPlaying", "playstate", "play_state", "bt_play_state")

                    for (k in titleKeys) {
                        val v = extras.getString(k)
                        if (!v.isNullOrBlank()) { title = v.trim(); break }
                    }
                    for (k in artistKeys) {
                        val v = extras.getString(k)
                        if (!v.isNullOrBlank()) { artist = v.trim(); break }
                    }
                    for (k in albumKeys) {
                        val v = extras.getString(k)
                        if (!v.isNullOrBlank()) { album = v.trim(); break }
                    }
                    for (k in durKeys) {
                        val v = extras.get(k)
                        if (v is Number && v.toLong() > 0) { durationMs = v.toLong(); break }
                    }
                    for (k in playKeys) {
                        if (extras.containsKey(k)) {
                            val v = extras.get(k)
                            when (v) {
                                is Boolean -> isPlaying = v
                                is Number -> isPlaying = (v.toInt() == 1 || v.toInt() == 3)
                            }
                            if (isPlaying != null) break
                        }
                    }

                    var artResult: JoyMediaArtworkHelper.ArtworkResult? = null
                    var artUrlHint: String? = null

                    // Check if a MediaMetadata parcelable was sent (AVRCP Controller standard)
                    try {
                        @Suppress("DEPRECATION")
                        val meta = extras.getParcelable<MediaMetadata>("android.bluetooth.avrcp-controller.profile.extra.METADATA")
                            ?: extras.getParcelable<MediaMetadata>("metadata")
                        if (meta != null) {
                            (meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
                                ?.let { if (it.isNotBlank()) title = it.trim() }
                            (meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
                                ?.let { if (it.isNotBlank()) artist = it.trim() }
                            (meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
                                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
                                ?.let { if (it.isNotBlank()) album = it.trim() }
                            val d = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                            if (d > 0) durationMs = d
                            val pair = JoyMediaArtworkHelper.processMetadataArtwork(this@MainActivity, meta)
                            artResult = pair.first
                            artUrlHint = pair.second
                        }
                    } catch (_: Throwable) {}

                    if (artResult == null) {
                        val artKeys = listOf(
                            "android.bluetooth.avrcp-controller.profile.extra.ARTWORK",
                            "artwork",
                            "cover",
                            "album_art",
                            "bitmap"
                        )
                        for (k in artKeys) {
                            if (!extras.containsKey(k)) continue
                            @Suppress("DEPRECATION")
                            val raw = extras.get(k)
                            when (raw) {
                                is Bitmap -> {
                                    artResult = JoyMediaArtworkHelper.processBitmapToArtwork(raw)
                                    if (artResult != null) break
                                }
                                is Uri -> {
                                    val bytes = try {
                                        contentResolver.openInputStream(raw)?.use { it.readBytes() }
                                    } catch (_: Throwable) { null }
                                    artResult = JoyMediaArtworkHelper.processBytesToArtwork(bytes)
                                    if (artResult != null) break
                                }
                                is String -> {
                                    val trimmed = raw.trim()
                                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                        artUrlHint = trimmed
                                    }
                                }
                            }
                        }
                    }

                    try {
                        @Suppress("DEPRECATION")
                        val pb = extras.getParcelable<PlaybackState>("android.bluetooth.avrcp-controller.profile.extra.PLAYBACK")
                            ?: extras.getParcelable<PlaybackState>("playback_state")
                        if (pb != null) {
                            isPlaying = (pb.state == PlaybackState.STATE_PLAYING)
                        }
                    } catch (_: Throwable) {}

                    if (!title.isNullOrBlank() || isPlaying != null) {
                        val durSec = if (durationMs > 1000) (durationMs / 1000).toInt() else durationMs.toInt()
                        bridge.updateLiveBtMetadata(
                            title = title,
                            artist = artist,
                            album = album,
                            durationSec = durSec,
                            isPlaying = isPlaying,
                            sourceAction = action,
                            coverDataUri = artResult?.dataUri,
                            color1 = artResult?.color1,
                            color2 = artResult?.color2,
                            artUrlHint = artUrlHint
                        )
                    }
                } else {
                    // Nothing in extras
                }

                // Check radio frequency broadcasts from ZXDZ_01 / TS / XYAuto / AutoChips MCU
                if (action.contains("radio", true) || action.contains("fm", true)) {
                    val freqExtra = extras?.get("frequency") ?: extras?.get("freq") ?: extras?.get("cur_freq")
                    if (freqExtra is Number) {
                        val rawFreq = freqExtra.toDouble()
                        val mhz = if (rawFreq > 1000) rawFreq / 1000.0 else if (rawFreq > 200) rawFreq / 10.0 else rawFreq
                        if (mhz in 87.0..108.5) {
                            runOnUiThread {
                                webView.evaluateJavascript("if (typeof window.onMcuRadioUpdate === 'function') window.onMcuRadioUpdate($mhz);", null)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w("JoyOS", "btMediaReceiver warning: ${e.message}")
            }
        }
    }

    private fun registerAutomotiveMediaReceivers() {
        try {
            val filter = IntentFilter().apply {
                // Standard Android & Music player intents
                addAction("com.android.music.metachanged")
                addAction("com.android.music.playstatechanged")
                addAction("com.android.music.playbackcomplete")
                addAction("com.android.music.queuechanged")
                // Android Bluetooth AVRCP Controller & A2DP Sink
                addAction("android.bluetooth.avrcp-controller.profile.action.TRACK_CHANGED")
                addAction("android.bluetooth.avrcp-controller.profile.action.PLAYBACK_STATE_CHANGED")
                addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED")
                addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
                // MediaTek / AutoChips AC8227L / ZXDZ_01 / WJ / TS / XYAuto OEM Broadcasts
                addAction("com.autochips.bluetooth.AvrcpControllerService.ACTION_TRACK_EVENT")
                addAction("com.autochips.bluetooth.btmusic.ACTION_META_CHANGED")
                addAction("com.autochips.bluetooth.PbapClientService.ACTION_EVENT")
                addAction("com.ts.bt.music.metachanged")
                addAction("com.ts.bt.playstatechanged")
                addAction("com.xyauto.broadcast.bt.music.info")
                addAction("com.xyauto.broadcast.bt.playstate")
                addAction("com.microntek.bt.report")
                addAction("com.fyt.bt.music")
                // OEM FM Radio & MCU state broadcasts
                addAction("com.ts.fmradio.FREQUENCY_CHANGED")
                addAction("com.xyauto.broadcast.radio.info")
                addAction("com.autochips.fmradio.intent.action.FREQ_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(btMediaReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(btMediaReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.w("JoyOS", "Could not register media broadcast receiver: ${e.message}")
        }
    }

    // ─── Bluetooth Profiles (A2DP, HFP Client, A2DP Sink), Phone Battery & Calls ──────────────

    private var btAdapter: BluetoothAdapter? = null
    private val btProfileProxies = java.util.concurrent.ConcurrentHashMap<Int, BluetoothProfile>()
    private var lastKnownPhoneBattery: Int = -1
    private var lastKnownPhoneSignal: Int = -1

    private val btProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (proxy != null) {
                btProfileProxies[profile] = proxy
                refreshBtTelemetryFromProfiles()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            btProfileProxies.remove(profile)
            refreshBtTelemetryFromProfiles()
        }
    }

    private fun initBluetoothProfiles() {
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return
            btAdapter = adapter
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val profiles = intArrayOf(
                BluetoothProfile.A2DP,
                BluetoothProfile.HEADSET,
                11, // BluetoothProfile.A2DP_SINK (@hide on standard SDK, standard on automotive)
                16  // BluetoothProfile.HEADSET_CLIENT (@hide on standard SDK, HFP Hands-Free Client)
            )
            for (profileId in profiles) {
                try {
                    adapter.getProfileProxy(this, btProfileListener, profileId)
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.w("JoyOS", "initBluetoothProfiles handled: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBtTelemetryFromProfiles() {
        try {
            var a2dpOk = false
            var hfpOk = false
            var activeAddr = ""
            var activeName = ""
            for ((profileId, proxy) in btProfileProxies) {
                val connected = try {
                    proxy.connectedDevices
                } catch (_: Throwable) {
                    emptyList()
                }
                if (connected.isNotEmpty()) {
                    val dev = connected.first()
                    if (profileId == BluetoothProfile.A2DP || profileId == 11) a2dpOk = true
                    if (profileId == BluetoothProfile.HEADSET || profileId == 16) hfpOk = true
                    if (activeAddr.isEmpty()) {
                        activeAddr = dev.address ?: ""
                        activeName = try { dev.name ?: "" } catch (_: Throwable) { "" }
                    }
                }
            }
            bridge.updateBluetoothTelemetry(
                battery = lastKnownPhoneBattery,
                signal = lastKnownPhoneSignal,
                deviceName = activeName.ifEmpty { null },
                deviceAddress = activeAddr.ifEmpty { null },
                a2dp = a2dpOk,
                hfp = hfpOk
            )
        } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun connectBtDeviceProxy(address: String): Boolean {
        return try {
            val adapter = btAdapter ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val device = adapter.getRemoteDevice(address) ?: return false
            var anyRequested = false
            val order = intArrayOf(16, 11, BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
            for (profileId in order) {
                val proxy = btProfileProxies[profileId] ?: continue
                try {
                    val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    val ok = m.invoke(proxy, device) as? Boolean ?: false
                    if (ok) anyRequested = true
                } catch (_: Throwable) {}
            }
            anyRequested
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectBtDeviceProxy(address: String): Boolean {
        return try {
            val adapter = btAdapter ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val device = adapter.getRemoteDevice(address) ?: return false
            var anyRequested = false
            for ((_, proxy) in btProfileProxies) {
                try {
                    val m = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                    val ok = m.invoke(proxy, device) as? Boolean ?: false
                    if (ok) anyRequested = true
                } catch (_: Throwable) {}
            }
            anyRequested
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun answerBtOrTelecomCall(): Boolean {
        return try {
            val hfpClient = btProfileProxies[16] ?: return false
            val devices = try { hfpClient.connectedDevices } catch (_: Throwable) { emptyList() }
            val dev = devices.firstOrNull() ?: return false
            val acceptMethod = hfpClient.javaClass.getMethod("acceptCall", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            (acceptMethod.invoke(hfpClient, dev, 0) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun endBtOrTelecomCall(): Boolean {
        return try {
            val hfpClient = btProfileProxies[16] ?: return false
            val devices = try { hfpClient.connectedDevices } catch (_: Throwable) { emptyList() }
            val dev = devices.firstOrNull() ?: return false
            val currentCalls = try {
                @Suppress("UNCHECKED_CAST")
                hfpClient.javaClass.getMethod("getCurrentCalls", BluetoothDevice::class.java).invoke(hfpClient, dev) as? List<Any?>
            } catch (_: Throwable) { null }
            val firstCall = currentCalls?.firstOrNull()
            val terminateMethod = hfpClient.javaClass.methods.firstOrNull {
                it.name == "terminateCall" && it.parameterTypes.size == 2
            } ?: return false
            (terminateMethod.invoke(hfpClient, dev, firstCall) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun dialBtHfpCall(number: String): Boolean {
        return try {
            val hfpClient = btProfileProxies[16] ?: return false
            val devices = try { hfpClient.connectedDevices } catch (_: Throwable) { emptyList() }
            val dev = devices.firstOrNull() ?: return false
            val dialMethod = hfpClient.javaClass.getMethod("dial", BluetoothDevice::class.java, String::class.java)
            val result = dialMethod.invoke(hfpClient, dev, number)
            val ok = (result is Boolean && result) || (result != null && result !is Boolean)
            if (ok) {
                bridge.updateCallState(state = "dialing", number = number, source = "hfp")
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }

    private val connectivityCallReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            try {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        @Suppress("DEPRECATION")
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val addr = dev?.address ?: ""
                        val name = try { dev?.name ?: "" } catch (_: Throwable) { "" }
                        val low = name.lowercase(Locale.ROOT)
                        val isObd = low.contains("obd") || low.contains("elm327") || low.contains("vgate") || low.contains("vlinker") || low.contains("veepeak") || low.contains("konnwei") || low.contains("carista")
                        if (!isObd) {
                            bridge.updateBluetoothTelemetry(
                                battery = lastKnownPhoneBattery,
                                signal = lastKnownPhoneSignal,
                                deviceName = name.ifEmpty { null },
                                deviceAddress = addr.ifEmpty { null }
                            )
                        }
                        refreshBtTelemetryFromProfiles()
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        @Suppress("DEPRECATION")
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val name = try { dev?.name ?: "" } catch (_: Throwable) { "" }
                        val low = name.lowercase(Locale.ROOT)
                        val isObd = low.contains("obd") || low.contains("elm327") || low.contains("vgate") || low.contains("vlinker") || low.contains("veepeak") || low.contains("konnwei") || low.contains("carista")
                        if (!isObd) {
                            lastKnownPhoneBattery = -1
                            lastKnownPhoneSignal = -1
                            bridge.updateBluetoothTelemetry(battery = -1, signal = -1, a2dp = false, hfp = false)
                        }
                        refreshBtTelemetryFromProfiles()
                    }
                    "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                        val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                        if (level in 0..100) {
                            lastKnownPhoneBattery = level
                            bridge.updateBluetoothTelemetry(battery = level)
                        }
                    }
                    "android.bluetooth.headsetclient.profile.action.AG_EVENT" -> {
                        var bat = -1
                        var sig = -1
                        val rawBat = intent.getIntExtra("android.bluetooth.headsetclient.extra.BATTERY_LEVEL", -1)
                        if (rawBat >= 0) {
                            // HFP AG_EVENT reports 0..5 by spec, some ROMs emit 0..100
                            bat = if (rawBat <= 5) (rawBat * 20).coerceIn(0, 100) else rawBat.coerceIn(0, 100)
                            lastKnownPhoneBattery = bat
                        }
                        val netStatus = intent.getIntExtra("android.bluetooth.headsetclient.extra.NETWORK_STATUS", -1)
                        val rawSig = intent.getIntExtra("android.bluetooth.headsetclient.extra.NETWORK_SIGNAL_STRENGTH", -1)
                        if (rawSig >= 0) {
                            sig = if (rawSig == 0) 0 else ((rawSig * 4 + 4) / 5).coerceIn(0, 4)
                            lastKnownPhoneSignal = sig
                        } else if (netStatus == 0) {
                            sig = 0
                            lastKnownPhoneSignal = 0
                        }
                        bridge.updateBluetoothTelemetry(
                            battery = if (bat >= 0) bat else null,
                            signal = if (sig >= 0) sig else null,
                            hfp = true
                        )
                    }
                    "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED" -> {
                        @Suppress("DEPRECATION")
                        val callObj = intent.getParcelableExtra<android.os.Parcelable>("android.bluetooth.headsetclient.extra.CALL")
                        if (callObj != null) {
                            val callStateInt = try { callObj.javaClass.getMethod("getState").invoke(callObj) as? Int ?: 7 } catch (_: Throwable) { 7 }
                            val callNumber = try { callObj.javaClass.getMethod("getNumber").invoke(callObj) as? String ?: "" } catch (_: Throwable) { "" }
                            val mapped = when (callStateInt) {
                                4, 5 -> "ringing"
                                2, 3 -> "dialing"
                                0, 1, 6 -> "active"
                                else -> "idle"
                            }
                            bridge.updateCallState(state = mapped, number = callNumber, source = "hfp")
                        }
                    }
                    "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED",
                    "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED" -> {
                        refreshBtTelemetryFromProfiles()
                    }
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                        val telState = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: ""
                        @Suppress("DEPRECATION")
                        val num = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                        val mapped = when (telState) {
                            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
                            TelephonyManager.EXTRA_STATE_OFFHOOK -> "active"
                            TelephonyManager.EXTRA_STATE_IDLE -> "idle"
                            else -> ""
                        }
                        if (mapped.isNotEmpty()) {
                            bridge.updateCallState(state = mapped, number = num, source = "telephony")
                        }
                    }
                    "com.autochips.bluetooth.hf.BluetoothHfUtility.action.CALL_STATE",
                    "com.autochips.bluetooth.BtCallState",
                    "com.ts.bt.call.state",
                    "com.xyauto.broadcast.bt.call.state",
                    "com.fyt.bt.call" -> {
                        val extras = intent.extras
                        val rawNum = extras?.getString("number")
                            ?: extras?.getString("phone")
                            ?: extras?.getString("num")
                            ?: ""
                        val rawName = extras?.getString("name")
                            ?: extras?.getString("contact")
                            ?: ""
                        val intState = listOf("state", "call_state", "status", "callstate").firstNotNullOfOrNull { k ->
                            @Suppress("DEPRECATION")
                            val v = extras?.get(k)
                            when (v) {
                                is Int -> v
                                is String -> v.toIntOrNull()
                                else -> null
                            }
                        }
                        val strState = extras?.getString("state")?.lowercase(Locale.ROOT)
                            ?: extras?.getString("call_state")?.lowercase(Locale.ROOT)
                            ?: ""
                        val mapped = when {
                            strState.contains("ring") || strState.contains("incom") || intState == 1 || intState == 4 -> "ringing"
                            strState.contains("dial") || strState.contains("out") || intState == 2 || intState == 3 -> "dialing"
                            strState.contains("act") || strState.contains("talk") || strState.contains("offhook") || intState == 0 -> "active"
                            strState.contains("idle") || strState.contains("hang") || strState.contains("end") || intState == 7 -> "idle"
                            else -> ""
                        }
                        if (mapped.isNotEmpty()) {
                            bridge.updateCallState(state = mapped, name = rawName, number = rawNum, source = "oem")
                        }
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    WifiManager.WIFI_STATE_CHANGED_ACTION,
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        runOnUiThread {
                            try {
                                webView.evaluateJavascript("if (typeof updateHardwareStatus === 'function') updateHardwareStatus();", null)
                            } catch (_: Throwable) {}
                        }
                    }
                    "android.media.VOLUME_CHANGED_ACTION" -> {
                        val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                        if (stream == AudioManager.STREAM_MUSIC) {
                            val vol = bridge.getMasterVolume()
                            runOnUiThread {
                                try {
                                    webView.evaluateJavascript("if (typeof window.onHardwareVolumeChanged === 'function') window.onHardwareVolumeChanged($vol);", null)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w("JoyOS", "Connectivity/call broadcast error: ${e.message}")
            }
        }
    }

    private fun registerConnectivityAndCallReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
                addAction("android.bluetooth.headsetclient.profile.action.AG_EVENT")
                addAction("android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED")
                addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                addAction("com.autochips.bluetooth.hf.BluetoothHfUtility.action.CALL_STATE")
                addAction("com.autochips.bluetooth.BtCallState")
                addAction("com.ts.bt.call.state")
                addAction("com.xyauto.broadcast.bt.call.state")
                addAction("com.fyt.bt.call")
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
                addAction("android.media.VOLUME_CHANGED_ACTION")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(connectivityCallReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(connectivityCallReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.w("JoyOS", "Could not register connectivity/call receiver: ${e.message}")
        }
    }

    private fun attachMediaSessionsIfAllowed() {
        if (!JoyMediaNotificationListener.isEnabled(this)) return
        try {
            val mgr = mediaSessionManager ?: (getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
                ?.also { mediaSessionManager = it } ?: return
            val listenerComp = ComponentName(this, JoyMediaNotificationListener::class.java)

            if (sessionsChangedListener == null) {
                sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                    runOnUiThread {
                        bindMediaControllers(controllers ?: emptyList())
                    }
                }
                mgr.addOnActiveSessionsChangedListener(sessionsChangedListener!!, listenerComp)
            }

            val current = mgr.getActiveSessions(listenerComp)
            bindMediaControllers(current)
        } catch (se: SecurityException) {
            Log.d("JoyOS", "MediaSession access pending user notification permission: ${se.message}")
        } catch (e: Throwable) {
            Log.w("JoyOS", "attachMediaSessions warning: ${e.message}")
        }
    }

    private fun unregisterMediaControllerCallbacks() {
        controllerCallbacks.forEach { (ctrl, cb) ->
            try { ctrl.unregisterCallback(cb) } catch (_: Throwable) {}
        }
        controllerCallbacks.clear()
    }

    private fun bindMediaControllers(controllers: List<MediaController>) {
        unregisterMediaControllerCallbacks()
        activeControllers = controllers.filter { it.packageName != packageName }

        activeControllers.forEach { ctrl ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    val primary = selectPrimaryMediaController()
                    if (primary == null || primary.sessionToken == ctrl.sessionToken) {
                        syncFromMediaController(ctrl)
                    }
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val primary = selectPrimaryMediaController()
                    if (primary == null || primary.sessionToken == ctrl.sessionToken || state?.state == PlaybackState.STATE_PLAYING) {
                        syncFromMediaController(ctrl)
                    }
                }

                override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
                    val primary = selectPrimaryMediaController()
                    if (primary == null || primary.sessionToken == ctrl.sessionToken) {
                        syncFromMediaController(ctrl)
                    }
                }
            }
            try {
                ctrl.registerCallback(cb)
                controllerCallbacks[ctrl] = cb
            } catch (_: Throwable) {}
        }

        selectPrimaryMediaController()?.let { syncFromMediaController(it) }
    }

    private fun selectPrimaryMediaController(): MediaController? {
        if (activeControllers.isEmpty()) return null
        return activeControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: activeControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            ?: activeControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED && it.metadata != null }
            ?: activeControllers.firstOrNull { it.metadata != null }
            ?: activeControllers.firstOrNull()
    }

    private fun extractQueueSliceJson(ctrl: MediaController, currentTitle: String?): String {
        return try {
            val rawQueue = ctrl.queue ?: return "[]"
            if (rawQueue.isEmpty()) return "[]"
            val activeId = ctrl.playbackState?.activeQueueItemId ?: -1L
            var activeIdx = if (activeId >= 0L) rawQueue.indexOfFirst { it.queueId == activeId } else -1
            if (activeIdx < 0 && !currentTitle.isNullOrBlank()) {
                activeIdx = rawQueue.indexOfFirst { item ->
                    val qt = AndroidBridge.cleanMediaField(item.description?.title?.toString())
                    qt.equals(currentTitle, ignoreCase = true)
                }
            }
            if (activeIdx < 0) activeIdx = 0
            val start = (activeIdx - 2).coerceAtLeast(0)
            val end = (activeIdx + 2).coerceAtMost(rawQueue.size - 1)
            val arr = JSONArray()
            for (idx in start..end) {
                val desc = rawQueue[idx].description ?: continue
                val qTitle = AndroidBridge.cleanMediaField(desc.title?.toString())
                if (qTitle.isEmpty()) continue
                val qArtist = AndroidBridge.cleanMediaField(desc.subtitle?.toString())
                val qAlbum = AndroidBridge.cleanMediaField(desc.description?.toString())
                arr.put(JSONObject().apply {
                    put("title", qTitle)
                    put("artist", qArtist)
                    put("album", qAlbum)
                    put("active", idx == activeIdx)
                    put("offset", idx - activeIdx)
                })
            }
            arr.toString()
        } catch (_: Throwable) {
            "[]"
        }
    }

    private fun syncFromMediaController(ctrl: MediaController) {
        try {
            val meta = ctrl.metadata
            val pb = ctrl.playbackState

            val title = AndroidBridge.cleanMediaField(
                meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            )
            val artist = AndroidBridge.cleanMediaField(
                meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            )
            val album = AndroidBridge.cleanMediaField(
                meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            )

            val rawDur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val durSec = if (rawDur > 1000) (rawDur / 1000).toInt() else rawDur.toInt()
            val posSec = pb?.position?.let { if (it >= 0) (it / 1000).toInt() else -1 } ?: -1
            val isPlaying: Boolean? = pb?.let {
                it.state == PlaybackState.STATE_PLAYING || it.state == PlaybackState.STATE_BUFFERING
            }
            val supportsSeek = pb == null || (pb.actions and PlaybackState.ACTION_SEEK_TO) != 0L

            if (title.isEmpty() && isPlaying == null) return

            val trackKey = "${ctrl.packageName}|$title|$artist|$album"
            if (trackKey != lastSessionTrackKey || lastSessionArt == null) {
                val (artRes, artHint) = JoyMediaArtworkHelper.processMetadataArtwork(this, meta)
                lastSessionTrackKey = trackKey
                lastSessionArt = artRes
                lastSessionArtHint = artHint
            }

            val queueJson = extractQueueSliceJson(ctrl, title)

            bridge.updateLiveBtMetadata(
                title = title,
                artist = artist,
                album = album,
                durationSec = durSec,
                isPlaying = isPlaying,
                sourceAction = "MediaSession",
                positionSec = posSec,
                coverDataUri = lastSessionArt?.dataUri,
                color1 = lastSessionArt?.color1,
                color2 = lastSessionArt?.color2,
                sourceApp = ctrl.packageName,
                artUrlHint = lastSessionArtHint,
                queueJson = queueJson,
                supportsSeek = supportsSeek
            )
        } catch (e: Throwable) {
            Log.w("JoyOS", "syncFromMediaController warning: ${e.message}")
        }
    }

    private fun handleSessionTransportAction(action: String): Boolean {
        val ctrl = selectPrimaryMediaController() ?: return false
        return try {
            val controls = ctrl.transportControls
            when (action) {
                "play" -> {
                    bridge.activateBtAudioSink()
                    controls.play()
                }
                "pause" -> controls.pause()
                "toggle" -> {
                    val state = ctrl.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                        controls.pause()
                    } else {
                        bridge.activateBtAudioSink()
                        controls.play()
                    }
                }
                "next" -> controls.skipToNext()
                "prev" -> controls.skipToPrevious()
                else -> return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun seekSessionPosition(posMs: Long): Boolean {
        val ctrl = selectPrimaryMediaController() ?: return false
        val pb = ctrl.playbackState
        if (pb != null && (pb.actions and PlaybackState.ACTION_SEEK_TO) == 0L) {
            return false
        }
        return try {
            ctrl.transportControls.seekTo(posMs.coerceAtLeast(0L))
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try { tpmsReceiver.handleUsbAttachIntent(intent) } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            try { hideSystemUI() } catch (_: Throwable) {}
        }
    }

    override fun onResume() {
        super.onResume()
        try { hideSystemUI() } catch (_: Throwable) {}
        try { gpsManager.startTracking() } catch (_: Throwable) {}
        try { obdManager.start(lifecycleScope) } catch (_: Throwable) {}
        try { tpmsReceiver.register() } catch (_: Throwable) {}
        try { attachMediaSessionsIfAllowed() } catch (_: Throwable) {}
    }

    override fun onPause() {
        super.onPause()
        try { gpsManager.stopTracking() } catch (_: Throwable) {}
        try { obdManager.stop() } catch (_: Throwable) {}
        try { tpmsReceiver.unregister() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { JoyMediaNotificationListener.setOnListenerReadyCallback(null) } catch (_: Throwable) {}
        try { JoyMediaNotificationListener.setOnCallNotificationCallback(null) } catch (_: Throwable) {}
        try { unregisterMediaControllerCallbacks() } catch (_: Throwable) {}
        try {
            sessionsChangedListener?.let { mediaSessionManager?.removeOnActiveSessionsChangedListener(it) }
        } catch (_: Throwable) {}
        try { unregisterReceiver(btMediaReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(connectivityCallReceiver) } catch (_: Throwable) {}
        try {
            for ((profileId, proxy) in btProfileProxies) {
                try { btAdapter?.closeProfileProxy(profileId, proxy) } catch (_: Throwable) {}
            }
            btProfileProxies.clear()
        } catch (_: Throwable) {}
        try { dspEngine.release() } catch (_: Throwable) {}
    }

    private fun ensureAutomotivePermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), 1001)
        } else {
            try { gpsManager.startTracking() } catch (_: Throwable) {}
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            try { gpsManager.startTracking() } catch (_: Throwable) {}
            try { obdManager.start(lifecycleScope) } catch (_: Throwable) {}
            try { tpmsReceiver.register() } catch (_: Throwable) {}
            try { initBluetoothProfiles() } catch (_: Throwable) {}
            runOnUiThread {
                try {
                    webView.evaluateJavascript(
                        "if (typeof refreshMusicFromDevice === 'function') refreshMusicFromDevice(); if (typeof renderFilesManagerUI === 'function') renderFilesManagerUI(); if (typeof updateHardwareStatus === 'function') updateHardwareStatus();",
                        null
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(0xFF0A0E17.toInt())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                } catch (_: Throwable) {}
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                displayZoomControls = false
                builtInZoomControls = false
                try {
                    val baseUa = userAgentString ?: "Mozilla/5.0"
                    if (!baseUa.contains("JoyOS")) {
                        userAgentString = "$baseUa JoyOS/1.1.2"
                    }
                } catch (_: Throwable) {}
            }

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val urlStr = request?.url?.toString() ?: return null
                    if (!isMapTileRequest(urlStr)) return null
                    return serveCachedMapTile(urlStr)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    try {
                        val initialVol = bridge.getMasterVolume()
                        evaluateJavascript(
                            "if (typeof state !== 'undefined') { state.vol = $initialVol; if (typeof renderAll === 'function') renderAll(); } if (typeof initNativeHardwareMode === 'function') initNativeHardwareMode();",
                            null
                        )
                    } catch (_: Throwable) {}
                }
            }

            addJavascriptInterface(bridge, "AndroidBridge")
        }
    }

    private val mapTileCacheDir: File by lazy {
        File(cacheDir, "joy_map_tiles").apply { if (!exists()) mkdirs() }
    }

    private fun isMapTileRequest(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return (lower.startsWith("http://") || lower.startsWith("https://")) && (
            lower.contains("google.com/vt") ||
                lower.contains("tile.openstreetmap.fr/") ||
                lower.contains("tile.openstreetmap.org/") ||
                lower.contains("arcgisonline.com/arcgis/rest/services/")
            )
    }

    private fun detectImageMime(bytes: ByteArray): String {
        return if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            "image/jpeg"
        } else {
            "image/png"
        }
    }

    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun fetchTileBytes(tileUrl: String): Pair<ByteArray, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(tileUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 5500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "JoyOS-Launcher/1.1 (ChevroletJoy)")
                setRequestProperty("Accept", "image/png,image/jpeg,image/*,*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 64) return null
            val mime = conn.contentType?.substringBefore(';')?.trim()?.ifEmpty { detectImageMime(bytes) } ?: detectImageMime(bytes)
            bytes to mime
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun buildTileFallbacks(originalUrl: String): List<String> {
        val urls = mutableListOf(originalUrl)
        if (originalUrl.startsWith("https://", ignoreCase = true) && originalUrl.contains("google.com/vt", ignoreCase = true)) {
            urls.add("http://" + originalUrl.substring(8))
        }
        val gMatch = Regex("[?&]x=(\\d+)&y=(\\d+)&z=(\\d+)").find(originalUrl)
        if (gMatch != null) {
            val (x, y, z) = gMatch.destructured
            val isSat = originalUrl.contains("lyrs=y", ignoreCase = true) || originalUrl.contains("lyrs=s", ignoreCase = true)
            if (isSat) {
                urls.add("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x")
            } else {
                urls.add("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$z/$y/$x")
                urls.add("https://a.tile.openstreetmap.fr/hot/$z/$x/$y.png")
            }
        }
        val zxyMatch = Regex("/(\\d+)/(\\d+)/(\\d+)\\.png").find(originalUrl)
        if (zxyMatch != null && (originalUrl.contains("tile.openstreetmap.org", ignoreCase = true) || originalUrl.contains("tile.openstreetmap.fr", ignoreCase = true))) {
            val (z, x, y) = zxyMatch.destructured
            urls.add("http://mt1.google.com/vt/lyrs=m&hl=es&x=$x&y=$y&z=$z")
        }
        return urls.distinct()
    }

    private fun serveCachedMapTile(originalUrl: String): WebResourceResponse? {
        val key = sha256Hex(originalUrl)
        val cacheFile = File(mapTileCacheDir, "$key.tile")
        val maxAgeMs = 14L * 24L * 3600L * 1000L
        val now = System.currentTimeMillis()
        if (cacheFile.exists() && cacheFile.length() > 64 && (now - cacheFile.lastModified()) < maxAgeMs) {
            return try {
                val data = cacheFile.readBytes()
                val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=1209600")
                WebResourceResponse(detectImageMime(data), null, 200, "OK", headers, ByteArrayInputStream(data))
            } catch (_: Throwable) {
                null
            }
        }

        for (candidate in buildTileFallbacks(originalUrl)) {
            val fetched = fetchTileBytes(candidate)
            if (fetched != null) {
                val (bytes, mime) = fetched
                try {
                    val tmp = File(mapTileCacheDir, "$key.tmp")
                    tmp.writeBytes(bytes)
                    if (cacheFile.exists()) cacheFile.delete()
                    tmp.renameTo(cacheFile)
                } catch (_: Throwable) {}
                val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=1209600")
                return WebResourceResponse(mime, null, 200, "OK", headers, ByteArrayInputStream(bytes))
            }
        }

        // Offline fallback: serve stale disk tile if present so previously visited routes stay visible
        if (cacheFile.exists() && cacheFile.length() > 64) {
            return try {
                val data = cacheFile.readBytes()
                val headers = mapOf("Access-Control-Allow-Origin" to "*")
                WebResourceResponse(detectImageMime(data), null, 200, "OK", headers, ByteArrayInputStream(data))
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun isHotUpdateCompatible(htmlText: String): Boolean {
        if (!htmlText.contains("onBtMusicMetadata") || !htmlText.contains("inspectOemHardware")) return false
        val marker = Regex("JOYOS_PROD_VER=([0-9]+\\.[0-9]+\\.[0-9]+)").find(htmlText)?.groupValues?.getOrNull(1) ?: return false
        val installed = bridge.getAppVersion()
        return compareSemVer(marker, installed) >= 0
    }

    private fun compareSemVer(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun loadJoyOS() {
        try {
            // Check if a verified, up-to-date hot-update exists in internal storage; otherwise load bundled production assets
            val liveUpdateFile = File(filesDir, "joy_os_live.html")
            if (liveUpdateFile.exists() && liveUpdateFile.length() > 1024) {
                val htmlText = liveUpdateFile.readText(Charsets.UTF_8)
                if (isHotUpdateCompatible(htmlText)) {
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/www/",
                        htmlText,
                        "text/html",
                        "UTF-8",
                        null
                    )
                    return
                } else {
                    // Delete stale cached hot-update so newer bundled APK assets take precedence
                    try { liveUpdateFile.delete() } catch (_: Throwable) {}
                }
            }

            // Load bundled production assets from assets/www/index.html
            webView.loadUrl("file:///android_asset/www/index.html")
        } catch (e: Throwable) {
            Log.e("JoyOS", "Failed to load JoyOS webview content: ${e.message}")
            try { webView.loadUrl("file:///android_asset/www/index.html") } catch (_: Throwable) {}
        }
    }

    private fun applyScreenTimeoutMode(mode: String) {
        screenTimeoutMode = when (mode.lowercase()) {
            "1m", "1 min" -> "1m"
            "5m", "5 min" -> "5m"
            else -> "never"
        }
        try {
            getSharedPreferences("joyos_system_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("screen_timeout_mode", screenTimeoutMode)
                .apply()
            if (screenTimeoutMode == "never") {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            if (android.provider.Settings.System.canWrite(this)) {
                val timeoutMs = when (screenTimeoutMode) {
                    "1m" -> 60_000
                    "5m" -> 300_000
                    else -> 1_800_000
                }
                android.provider.Settings.System.putInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                    timeoutMs
                )
            }
        } catch (e: Throwable) {
            Log.w("JoyOS", "applyScreenTimeoutMode handled: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (screenTimeoutMode == "never") {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun scheduleBackgroundOtaCheck() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(8000)
                val autoCheck = getSharedPreferences("joyos_system_prefs", Context.MODE_PRIVATE)
                    .getBoolean("ota_auto_wifi", true)
                if (!autoCheck) {
                    Log.i("JoyOS", "Background OTA check disabled by user setting")
                    return@launch
                }
                val res = otaManager.checkLatestRelease(bridge.getEffectiveInstalledVersion())
                if (res.isSuccess && res.getOrNull()?.isNewer == true) {
                    val info = res.getOrNull()!!
                    val json = JSONObject().apply {
                        put("status", "success")
                        put("isNewer", true)
                        put("version", info.version)
                        put("tagName", info.tagName)
                        put("changelog", info.changelog)
                        put("apkUrl", info.apkUrl ?: "")
                        put("liveHtmlUrl", info.liveHtmlUrl ?: "")
                    }
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if (typeof window.onOtaUpdateAvailable === 'function') window.onOtaUpdateAvailable($json);",
                            null
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.w("JoyOS", "Silent background OTA check skipped: ${e.message}")
            }
        }
    }

    private fun scheduleBootHardwareOptimization() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(4000)
                val report = hardwareOptimizer.runOptimization()
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if (typeof window.onHardwareOptimizationComplete === 'function') window.onHardwareOptimizationComplete(${report.toJson()});",
                        null
                    )
                }
            } catch (e: Throwable) {
                Log.w("JoyOS", "Silent boot hardware optimization skipped: ${e.message}")
            }
        }
    }
}
