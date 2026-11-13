package com.joyos.launcher.services

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Enables MediaSessionManager.getActiveSessions() access so JoyOS can mirror
 * real song title, artist, album, playback position and album artwork from Spotify,
 * YouTube Music, Bluetooth audio players and Android media apps.
 */
class JoyMediaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "JoyMediaNotifListener"

        @Volatile
        private var callbackRef: WeakReference<(() -> Unit)>? = null

        fun setOnListenerReadyCallback(callback: (() -> Unit)?) {
            callbackRef = if (callback != null) WeakReference(callback) else null
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
        } catch (_: Throwable) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // MediaController callbacks handle live metadata/artwork updates cleanly without spam
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Handled by MediaSessionManager.OnActiveSessionsChangedListener
    }
}
