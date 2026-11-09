package com.kexpspotifysync.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kexpspotifysync.MainActivity
import com.kexpspotifysync.data.SyncPreferences

object NotificationHelper {
    private const val CHANNEL_WATCHDOG = "kexp_sync_watchdog_alerts"
    private const val CHANNEL_SYNC_STATUS = "kexp_sync_status"

    private const val NOTIF_ID_WATCHDOG = 1001
    private const val NOTIF_ID_ERROR = 1002
    private const val NOTIF_ID_SUCCESS = 1003

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val watchdogChannel = NotificationChannel(
                CHANNEL_WATCHDOG,
                "KEXP App Update & Watchdog Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts you immediately if a KEXP app update changes button IDs or breaks favorite detection."
            }

            val statusChannel = NotificationChannel(
                CHANNEL_SYNC_STATUS,
                "Spotify Like Confirmations",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Subtle confirmation when a KEXP song is liked on Spotify."
            }

            nm.createNotificationChannels(listOf(watchdogChannel, statusChannel))
        }
    }

    private fun mainActivityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Fired when KEXP is playing or open, but the expected Favorite CustomAction or UI ID
     * is missing (e.g., after a KEXP app update) so the user is never left guessing.
     */
    fun notifyWatchdogAlert(context: Context, title: String, message: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_WATCHDOG)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainActivityIntent(context))
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID_WATCHDOG, notification)
    }

    fun notifySyncError(context: Context, title: String, message: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_WATCHDOG)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainActivityIntent(context))
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID_ERROR, notification)
    }

    fun notifySyncSuccess(context: Context, artist: String, track: String, source: String) {
        if (!SyncPreferences.shouldNotifyOnSuccess(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Liked on Spotify 💚")
            .setContentText("$track — $artist ($source)")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityIntent(context))
            .setTimeoutAfter(8_000L) // Auto-dismiss after 8 seconds
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID_SUCCESS, notification)
    }

    fun notifyUnlikedSuccess(context: Context, artist: String, track: String, source: String) {
        if (!SyncPreferences.shouldNotifyOnSuccess(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle("Removed from Spotify Library 💔")
            .setContentText("$track — $artist ($source)")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityIntent(context))
            .setTimeoutAfter(8_000L)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID_SUCCESS, notification)
    }
}
