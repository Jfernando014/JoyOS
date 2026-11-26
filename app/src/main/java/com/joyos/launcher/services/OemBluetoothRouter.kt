package com.joyos.launcher.services

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * Handles proprietary Chinese head-unit (AC8227L / ZXDZ / Clinkway / HCN / AutoChips)
 * Bluetooth A2DP speaker-mux routing and hidden local-source metadata services.
 *
 * On many AC8227L boards, the MCU audio switch only routes Bluetooth A2DP to the car speakers
 * once the factory Bluetooth Music activity has been brought to the foreground at least once
 * since boot (or after using factory FM Radio). This helper performs a silent, animation-free
 * 1.8s foreground handoff and immediately returns JoyOS to the front via REORDER_TASKS.
 */
class OemBluetoothRouter(
    private val activity: Activity,
    private val onMetadataUpdate: (
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        positionSec: Int,
        isPlaying: Boolean?,
        artworkBytes: ByteArray?,
        sourceLabel: String
    ) -> Unit,
    private val isCallActive: () -> Boolean
) {

    companion object {
        private const val TAG = "OemBtRouter"
        private const val CLW_PKG = "com.clw.btmusic"
        private const val CLW_SVC = "com.clw.btmusic.BTPlaybackService"
        private const val CLW_DESC = "com.clw.btmusic.IBTPlaybackService"

        private const val TX_TOGGLE = 1
        private const val TX_PREV = 2
        private const val TX_NEXT = 3
        private const val TX_IS_PLAYING = 4
        private const val TX_IS_A2DP = 5
        private const val TX_TITLE = 10
        private const val TX_ARTIST = 11
        private const val TX_ALBUM = 12
        private const val TX_DURATION = 13
        private const val TX_POSITION = 14
        private const val TX_REFRESH = 15
        private const val TX_BT_MODE = 16

        private val OTHER_MCU_SOURCE_HINTS = listOf(
            "fmradio", "fmam", ".radio", ".aux", "avin", "dvdplayer", "backcar"
        )
        private val CAMERA_OR_CALL_HINTS = listOf(
            "backcar", "avin", "camera", "dvr", "btphone", "incallui", "dialer"
        )
    }

    private val appCtx = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bootRealtimeMs = SystemClock.elapsedRealtime()

    @Volatile
    var isBtAudioRouted: Boolean = false
        private set

    private var lastRouteAttemptMs: Long = 0L
    private var cachedBtActivity: ComponentName? = null
    private var btActivitySearched: Boolean = false

    // Optional Clinkway Binder state
    private var clwBinder: IBinder? = null
    private var clwBound: Boolean = false
    private var clwWasPlaying: Boolean? = null

    // Optional HCN android.sourceservice.SourceInfo reflection state
    private var hcnSourceInfo: Any? = null
    private var hcnGetMusicInfo: Method? = null
    private var hcnSetListener: Method? = null
    private var lastHcnFile: String = ""
    private var lastHcnState: String = ""

    private val clwConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            clwBinder = service
            pollClwBinder()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clwBinder = null
            clwWasPlaying = null
        }
    }

    private val periodicCheck = object : Runnable {
        override fun run() {
            checkForegroundSourceReset()
            pollClwBinder()
            mainHandler.postDelayed(this, 3000L)
        }
    }

    fun start() {
        bindClwIfPresent()
        initHcnSourceInfoIfPresent()
        mainHandler.removeCallbacks(periodicCheck)
        mainHandler.postDelayed(periodicCheck, 3500L)
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        if (clwBound) {
            try { appCtx.unbindService(clwConnection) } catch (_: Throwable) {}
            clwBound = false
            clwBinder = null
        }
        if (hcnSourceInfo != null && hcnSetListener != null) {
            try { hcnSetListener?.invoke(hcnSourceInfo, null) } catch (_: Throwable) {}
        }
    }

    fun notifyRadioOpened() {
        isBtAudioRouted = false
    }

    fun onPhoneA2dpConnected(autoRouteEnabled: Boolean) {
        if (!autoRouteEnabled || isBtAudioRouted) return
        val delayMs = if (SystemClock.elapsedRealtime() - bootRealtimeMs < 7500L) 4500L else 2400L
        mainHandler.postDelayed({
            if (!isBtAudioRouted) {
                ensureBtAudioRouted(manualTrigger = false)
            }
        }, delayMs)
    }

    /**
     * Sends direct transport commands to Clinkway binder if bound; returns true if handled.
     */
    fun sendDirectTransport(action: String): Boolean {
        val b = clwBinder ?: return false
        if (!b.isBinderAlive) return false
        val code = when (action) {
            "next" -> TX_NEXT
            "prev" -> TX_PREV
            "play", "pause", "toggle" -> TX_TOGGLE
            else -> return false
        }
        Thread {
            try {
                if (action == "play") transactVoid(b, TX_BT_MODE)
                transactVoid(b, code)
            } catch (_: Throwable) {}
        }.start()
        return true
    }

    /**
     * Opens the OEM Bluetooth Music activity briefly without animation so the MCU switches
     * physical speaker output to Bluetooth A2DP, then brings JoyOS right back to the front.
     */
    fun ensureBtAudioRouted(manualTrigger: Boolean): Boolean {
        if (isBtAudioRouted && !manualTrigger) return true

        val now = SystemClock.elapsedRealtime()
        if (!manualTrigger && now - bootRealtimeMs < 6200L) {
            return false
        }
        if (!manualTrigger && now - lastRouteAttemptMs < 18000L) {
            return false
        }
        if (isCallActive()) return false

        val topPkg = getTopPackageName()
        if (topPkg != null) {
            val lowTop = topPkg.lowercase(Locale.ROOT)
            if (CAMERA_OR_CALL_HINTS.any { lowTop.contains(it) }) {
                return false
            }
        }

        // First try Clinkway TX_BT_MODE if available
        clwBinder?.let { b ->
            if (b.isBinderAlive) {
                Thread {
                    try { transactVoid(b, TX_BT_MODE) } catch (_: Throwable) {}
                }.start()
            }
        }

        val targetComp = findOemBtMusicActivity() ?: return false
        lastRouteAttemptMs = now
        val joyTaskId = try { activity.taskId } catch (_: Throwable) { -1 }

        return try {
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = targetComp
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            activity.startActivity(launchIntent)
            activity.overridePendingTransition(0, 0)
            isBtAudioRouted = true
            Log.i(TAG, "OEM BT audio mux handoff started via ${targetComp.flattenToShortString()}")

            mainHandler.postDelayed({
                returnToJoyOs(joyTaskId, targetComp.packageName)
            }, 1850L)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "OEM BT handoff skipped: ${e.message}")
            false
        }
    }

    private fun returnToJoyOs(joyTaskId: Int, btPkg: String) {
        val currentTop = getTopPackageName()
        if (currentTop != null &&
            currentTop != appCtx.packageName &&
            currentTop != btPkg &&
            CAMERA_OR_CALL_HINTS.any { currentTop.lowercase(Locale.ROOT).contains(it) }
        ) {
            return
        }
        try {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null && joyTaskId >= 0) {
                am.moveTaskToFront(joyTaskId, 0)
            }
        } catch (_: Throwable) {}

        try {
            val backIntent = Intent(appCtx, activity.javaClass).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            appCtx.startActivity(backIntent)
        } catch (_: Throwable) {}
    }

    private fun findOemBtMusicActivity(): ComponentName? {
        if (btActivitySearched) return cachedBtActivity
        btActivitySearched = true
        return try {
            val pm = appCtx.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(mainIntent, 0)
            var best: ComponentName? = null
            var bestScore = 0
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == appCtx.packageName) continue
                val cls = ai.name ?: ""
                val label = try { ri.loadLabel(pm)?.toString() ?: "" } catch (_: Throwable) { "" }
                val lowAll = "$pkg/$cls $label".lowercase(Locale.ROOT)
                if (lowAll.contains("dialer") || lowAll.contains("phonebook") || lowAll.contains("setting")) {
                    continue
                }
                var score = 0
                if (pkg == CLW_PKG || lowAll.contains("btmusic") || lowAll.contains("btaudio")) score += 10
                if (lowAll.contains("bluetooth music") || lowAll.contains("música bluetooth") ||
                    lowAll.contains("musica bluetooth") || lowAll.contains("audio bluetooth") ||
                    lowAll.contains("bt music") || lowAll.contains("música bt")
                ) score += 9
                if ((pkg.contains("autochips") || pkg.contains("xyauto") || pkg.contains("ts") || pkg.contains("syu") || pkg.contains("fyt")) &&
                    (lowAll.contains("music") && lowAll.contains("bt") || lowAll.contains("a2dp"))
                ) score += 8
                if (score > bestScore) {
                    bestScore = score
                    best = ComponentName(pkg, cls)
                }
            }
            cachedBtActivity = best
            best
        } catch (_: Throwable) {
            null
        }
    }

    private fun checkForegroundSourceReset() {
        val top = getTopPackageName() ?: return
        if (top == appCtx.packageName) return
        val btComp = cachedBtActivity
        if (btComp != null && top == btComp.packageName) {
            isBtAudioRouted = true
            return
        }
        val low = top.lowercase(Locale.ROOT)
        if (OTHER_MCU_SOURCE_HINTS.any { low.contains(it) }) {
            isBtAudioRouted = false
        }
    }

    @Suppress("DEPRECATION")
    private fun getTopPackageName(): String? {
        return try {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val tasks = am.getRunningTasks(1)
            tasks?.firstOrNull()?.topActivity?.packageName
        } catch (_: Throwable) {
            null
        }
    }

    private fun bindClwIfPresent() {
        val exists = try {
            appCtx.packageManager.getPackageInfo(CLW_PKG, 0)
            true
        } catch (_: Throwable) {
            false
        }
        if (!exists || clwBound) return
        try {
            val intent = Intent().setClassName(CLW_PKG, CLW_SVC)
            clwBound = appCtx.bindService(intent, clwConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {
            clwBound = false
        }
    }

    private fun pollClwBinder() {
        val b = clwBinder ?: return
        if (!b.isBinderAlive) return
        Thread {
            try {
                val a2dpOn = transactBool(b, TX_IS_A2DP)
                if (!a2dpOn) {
                    clwWasPlaying = null
                    return@Thread
                }
                val playing = transactBool(b, TX_IS_PLAYING)
                val risingEdge = playing && clwWasPlaying == false
                clwWasPlaying = playing

                transactVoid(b, TX_REFRESH)
                val title = transactString(b, TX_TITLE)
                val artist = transactString(b, TX_ARTIST)
                val album = transactString(b, TX_ALBUM)
                val rawDur = transactLong(b, TX_DURATION)
                val rawPos = transactLong(b, TX_POSITION)
                val durSec = if (rawDur > 36000L) (rawDur / 1000L).toInt() else rawDur.toInt().coerceAtLeast(0)
                val posSec = if (rawPos > 36000L) (rawPos / 1000L).toInt() else rawPos.toInt().coerceAtLeast(-1)

                if (title.isNotEmpty() || playing) {
                    mainHandler.post {
                        onMetadataUpdate(title, artist, album, durSec, posSec, playing, null, CLW_PKG)
                        if (risingEdge && !isBtAudioRouted) {
                            ensureBtAudioRouted(manualTrigger = false)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }.start()
    }

    private fun initHcnSourceInfoIfPresent() {
        try {
            val cls = Class.forName("android.sourceservice.SourceInfo")
            val inst = cls.getMethod("getInstance").invoke(null) ?: return
            hcnSourceInfo = inst
            hcnGetMusicInfo = cls.getMethod("getMusicInfo")
            val listenerCls = Class.forName("android.sourceservice.SourceInfo\$OnEventListener")
            val setMethod = cls.getMethod("setOnEventListener", listenerCls)
            hcnSetListener = setMethod
            val proxy = Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(listenerCls),
                InvocationHandler { p, method, args ->
                    when (method.name) {
                        "onMusicEvent" -> {
                            val infoObj = args?.firstOrNull()
                            if (infoObj != null) handleHcnMusicInfo(infoObj)
                            null
                        }
                        "onSourceChanged" -> {
                            try {
                                val infoObj = hcnGetMusicInfo?.invoke(inst)
                                if (infoObj != null) handleHcnMusicInfo(infoObj)
                            } catch (_: Throwable) {}
                            null
                        }
                        "hashCode" -> System.identityHashCode(p)
                        "equals" -> args != null && args.isNotEmpty() && p === args[0]
                        "toString" -> "JoyOSOemRouter"
                        else -> null
                    }
                }
            )
            setMethod.invoke(inst, proxy)
        } catch (_: Throwable) {
            // Not an HCN board; no-op
        }
    }

    private fun handleHcnMusicInfo(infoObj: Any) {
        val filePath = readStringField(infoObj, "fileName")
        val stateStr = readStringField(infoObj, "state")
        val durMs = readLongField(infoObj, "duration")
        val posMs = readLongField(infoObj, "position")
        if (filePath.isEmpty()) return
        val playing = stateStr.equals("start", ignoreCase = true)
        val sameFile = filePath == lastHcnFile
        if (sameFile && stateStr == lastHcnState) return
        lastHcnFile = filePath
        lastHcnState = stateStr

        Thread {
            var title = filePath.substringAfterLast('/').substringBeforeLast('.')
            var artist = ""
            var album = ""
            var artBytes: ByteArray? = null
            if (!sameFile) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(filePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }?.let { title = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }?.let { artist = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }?.let { album = it }
                    artBytes = retriever.embeddedPicture
                } catch (_: Throwable) {
                } finally {
                    try { retriever.release() } catch (_: Throwable) {}
                }
            }
            val durSec = if (durMs > 1000L) (durMs / 1000L).toInt() else durMs.toInt().coerceAtLeast(0)
            val posSec = if (posMs >= 0L) (posMs / 1000L).toInt() else -1
            mainHandler.post {
                onMetadataUpdate(title, artist, album, durSec, posSec, playing, artBytes, "com.hcn.AutoMediaPlayer")
            }
        }.start()
    }

    private fun readStringField(obj: Any, name: String): String {
        return try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            (f.get(obj) as? String)?.trim() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun readLongField(obj: Any, name: String): Long {
        return try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            (f.get(obj) as? Number)?.toLong() ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun transactParcel(b: IBinder, code: Int): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CLW_DESC)
            b.transact(code, data, reply, 0)
            reply.readException()
            return reply
        } finally {
            data.recycle()
        }
    }

    private fun transactVoid(b: IBinder, code: Int) {
        transactParcel(b, code).recycle()
    }

    private fun transactBool(b: IBinder, code: Int): Boolean {
        val r = transactParcel(b, code)
        return try { r.readInt() != 0 } finally { r.recycle() }
    }

    private fun transactLong(b: IBinder, code: Int): Long {
        val r = transactParcel(b, code)
        return try { r.readLong() } finally { r.recycle() }
    }

    private fun transactString(b: IBinder, code: Int): String {
        val r = transactParcel(b, code)
        return try {
            val s = r.readString()?.trim() ?: ""
            if (s.equals("Not Provided", ignoreCase = true)) "" else s
        } finally {
            r.recycle()
        }
    }
}
