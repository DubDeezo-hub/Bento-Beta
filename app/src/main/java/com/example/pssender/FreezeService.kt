package com.example.pssender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps frozen values frozen while Bento is in the background (you are playing on the TV, the phone is in your
 * pocket): a foreground service with a notification and a Stop button, holding the processor and Wi-Fi awake. The
 * writing itself is MemorySession's freeze loop; this service only keeps the app alive while it runs.
 */
class FreezeService : Service() {
    private var locks: TransferLocks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, AppText.get(R.string.memory_freeze_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after startForegroundService(), whatever happens next
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        when {
            intent?.action == ACTION_STOP -> {
                MemorySession.stopAllFreezes()
                finish()
            }
            MemorySession.frozenCount == 0 -> finish()
            else -> {
                if (locks == null) locks = TransferLocks(this).also { it.acquire() }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        locks?.release()
        locks = null
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 5,
            Intent(this, FreezeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = MemorySession.frozenCount
        val game = MemorySession.gameName
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(AppText.get(R.string.memory_freeze_notif_title, n))
            .setContentText(if (game.isBlank()) AppText.get(R.string.memory_freeze_notif_text_plain) else AppText.get(R.string.memory_freeze_notif_text, game))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, AppText.get(R.string.memory_freeze_notif_stop), stop)
            .build()
    }

    override fun onDestroy() {
        locks?.release()
        locks = null
        super.onDestroy()
    }

    companion object {
        // Its own number: transfers use 42, chains 43, sharing 44
        const val NOTIF_ID = 45
        const val CHANNEL_ID = "memory_freeze"
        const val ACTION_STOP = "com.example.pssender.STOP_FREEZING"

        @Volatile
        private var running = false

        /** Starts the service, or refreshes its notification when it runs already. */
        fun update(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, FreezeService::class.java))
                running = true
            } catch (_: Exception) {
                // Not allowed from the background on some phones: freezing still works while Bento is open
            }
        }

        /**
         * Lets the service end. It is told rather than stopped from outside: a service started in the foreground
         * must reach startForeground() first, or Android stops the app.
         */
        fun stop(ctx: Context) {
            if (!running) return
            running = false
            try {
                ctx.startForegroundService(Intent(ctx, FreezeService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
