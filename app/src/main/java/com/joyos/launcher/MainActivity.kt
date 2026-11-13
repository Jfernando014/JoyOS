package com.joyos.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
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
import org.json.JSONObject
import java.io.File
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
            onSeekMediaPosition = { posMs -> seekSessionPosition(posMs) }
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
        JoyMediaNotificationListener.setOnListenerReadyCallback {
            runOnUiThread { attachMediaSessionsIfAllowed() }
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

    private fun syncFromMediaController(ctrl: MediaController) {
        try {
            val meta = ctrl.metadata
            val pb = ctrl.playbackState

            val title = meta?.let {
                it.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            }?.trim()
            val artist = meta?.let {
                it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            }?.trim()
            val album = meta?.let {
                it.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            }?.trim()

            val rawDur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val durSec = if (rawDur > 1000) (rawDur / 1000).toInt() else rawDur.toInt()
            val posSec = pb?.position?.let { if (it >= 0) (it / 1000).toInt() else -1 } ?: -1
            val isPlaying: Boolean? = pb?.let {
                it.state == PlaybackState.STATE_PLAYING || it.state == PlaybackState.STATE_BUFFERING
            }

            if (title.isNullOrBlank() && isPlaying == null) return

            val trackKey = "${ctrl.packageName}|${title ?: ""}|${artist ?: ""}|${album ?: ""}"
            if (trackKey != lastSessionTrackKey || lastSessionArt == null) {
                val (artRes, artHint) = JoyMediaArtworkHelper.processMetadataArtwork(this, meta)
                lastSessionTrackKey = trackKey
                lastSessionArt = artRes
                lastSessionArtHint = artHint
            }

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
                artUrlHint = lastSessionArtHint
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
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> {
                    if (ctrl.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause()
                    else controls.play()
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
        try { unregisterMediaControllerCallbacks() } catch (_: Throwable) {}
        try {
            sessionsChangedListener?.let { mediaSessionManager?.removeOnActiveSessionsChangedListener(it) }
        } catch (_: Throwable) {}
        try { unregisterReceiver(btMediaReceiver) } catch (_: Throwable) {}
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
                useWideViewPort = true
                loadWithOverviewMode = true
                displayZoomControls = false
                builtInZoomControls = false
                try {
                    val baseUa = userAgentString ?: "Mozilla/5.0"
                    if (!baseUa.contains("JoyOS")) {
                        userAgentString = "$baseUa JoyOS/1.1.0"
                    }
                } catch (_: Throwable) {}
            }

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
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
                val res = otaManager.checkLatestRelease(bridge.getAppVersion())
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
