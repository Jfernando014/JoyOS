package com.joyos.launcher.services

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Snapshot of an active or incoming call notification captured via NotificationListenerService.
 */
data class CallNotificationInfo(
    val status: String, // "ringing" | "active"
    val callerName: String,
    val callerNumber: String,
    val sourceApp: String,
    val photoDataUri: String,
    val hasAnswerAction: Boolean,
    val hasDeclineAction: Boolean
)

/**
 * Enables MediaSessionManager.getActiveSessions() access so JoyOS can mirror
 * real song title, artist, album, playback position and album artwork from Spotify,
 * YouTube Music, Bluetooth audio players and Android media apps, AND captures
 * incoming/ongoing calls from Phone/Dialer/WhatsApp/BT apps with answer/reject actions.
 */
class JoyMediaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "JoyMediaNotifListener"

        @Volatile
        private var callbackRef: WeakReference<(() -> Unit)>? = null

        @Volatile
        private var callCallbackRef: WeakReference<((CallNotificationInfo?) -> Unit)>? = null

        @Volatile
        private var activeCallKey: String? = null

        @Volatile
        private var activeAnswerIntent: PendingIntent? = null

        @Volatile
        private var activeDeclineIntent: PendingIntent? = null

        private val ANSWER_KEYWORDS = listOf("answer", "accept", "contestar", "responder", "aceptar", "atender")
        private val DECLINE_KEYWORDS = listOf("decline", "reject", "rechazar", "hang up", "hangup", "colgar", "finalizar", "end call", "end", "dismiss", "ignorar")
        private val CALL_APP_HINTS = listOf("dialer", "telecom", "phone", "incallui", "whatsapp", "telegram", "meet", "skype", "autochips.bluetooth", "btphone")

        fun setOnListenerReadyCallback(callback: (() -> Unit)?) {
            callbackRef = if (callback != null) WeakReference(callback) else null
        }

        fun setOnCallNotificationCallback(callback: ((CallNotificationInfo?) -> Unit)?) {
            callCallbackRef = if (callback != null) WeakReference(callback) else null
        }

        fun invokeAnswerAction(): Boolean {
            val pi = activeAnswerIntent ?: return false
            return try {
                pi.send()
                true
            } catch (e: Throwable) {
                Log.w(TAG, "invokeAnswerAction error: ${e.message}")
                false
            }
        }

        fun invokeDeclineAction(): Boolean {
            val pi = activeDeclineIntent ?: return false
            return try {
                pi.send()
                true
            } catch (e: Throwable) {
                Log.w(TAG, "invokeDeclineAction error: ${e.message}")
                false
            }
        }

        fun isEnabled(context: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: return false
                val expected = ComponentName(context, JoyMediaNotificationListener::class.java)
                flat.contains(expected.flattenToString()) || flat.contains(context.packageName)
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "JoyMediaNotificationListener connected")
        try {
            callbackRef?.get()?.invoke()
            // Inspect already active call notifications upon bind
            activeNotifications?.firstOrNull { isCallNotification(it) }?.let { handleCallPosted(it) }
        } catch (_: Throwable) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            if (isCallNotification(sbn)) {
                handleCallPosted(sbn)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "onNotificationPosted warning: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            if (sbn.key == activeCallKey) {
                activeCallKey = null
                activeAnswerIntent = null
                activeDeclineIntent = null
                callCallbackRef?.get()?.invoke(null)
            }
        } catch (_: Throwable) {}
    }

    private fun isCallNotification(sbn: StatusBarNotification): Boolean {
        val notif = sbn.notification ?: return false
        if (notif.category == Notification.CATEGORY_CALL) return true
        val pkgLower = sbn.packageName?.lowercase().orEmpty()
        if (CALL_APP_HINTS.none { pkgLower.contains(it) }) return false
        val actions = notif.actions ?: return false
        var hasDecline = false
        for (a in actions) {
            val t = a.title?.toString()?.lowercase().orEmpty()
            if (DECLINE_KEYWORDS.any { t.contains(it) } || ANSWER_KEYWORDS.any { t.contains(it) }) {
                hasDecline = true
                break
            }
        }
        return hasDecline && ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0 || notif.fullScreenIntent != null)
    }

    private fun handleCallPosted(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        var answerPi: PendingIntent? = null
        var declinePi: PendingIntent? = null
        notif.actions?.forEach { action ->
            val label = action.title?.toString()?.lowercase().orEmpty()
            if (answerPi == null && ANSWER_KEYWORDS.any { label.contains(it) }) {
                answerPi = action.actionIntent
            } else if (declinePi == null && DECLINE_KEYWORDS.any { label.contains(it) }) {
                declinePi = action.actionIntent
            }
        }

        val isIncoming = answerPi != null ||
            notif.fullScreenIntent != null ||
            rawText.contains("entrante", ignoreCase = true) ||
            rawText.contains("incoming", ignoreCase = true) ||
            rawText.contains("llamada de", ignoreCase = true)

        val status = if (isIncoming) "ringing" else "active"
        val looksLikeNumber = rawText.replace(Regex("[+()\\-\\s]"), "").all { it.isDigit() } && rawText.any { it.isDigit() }
        val callerName = if (rawTitle.isNotEmpty()) rawTitle else if (!looksLikeNumber && rawText.isNotEmpty()) rawText else "Llamada entrante"
        val callerNumber = if (looksLikeNumber) rawText else ""

        var photoUri = ""
        try {
            val largeBmp: Bitmap? = notif.getLargeIcon()?.loadDrawable(this)?.let { d ->
                (d as? BitmapDrawable)?.bitmap
            }
            if (largeBmp != null) {
                photoUri = JoyMediaArtworkHelper.processBitmapToArtwork(largeBmp)?.dataUri.orEmpty()
            }
        } catch (_: Throwable) {}

        activeCallKey = sbn.key
        activeAnswerIntent = answerPi
        activeDeclineIntent = declinePi

        callCallbackRef?.get()?.invoke(
            CallNotificationInfo(
                status = status,
                callerName = callerName,
                callerNumber = callerNumber,
                sourceApp = sbn.packageName.orEmpty(),
                photoDataUri = photoUri,
                hasAnswerAction = answerPi != null,
                hasDeclineAction = declinePi != null
            )
        )
    }
}
