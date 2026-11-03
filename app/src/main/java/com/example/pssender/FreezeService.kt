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
 * Keeps frozen values frozen while Crimson Multitool is in the background (you are playing on the TV, the phone is in your
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
            intent?.action == ACTION_TOGGLE -> {
                // A quick toggle from the notification: freeze or unfreeze one cheat without leaving the game
                val id = intent.getStringExtra(EXTRA_CHEAT)
                MemorySession.cheats.firstOrNull { it.id == id }?.let { MemorySession.setFrozen(it, !it.frozen) }
                if (MemorySession.frozenCount == 0 && !PlayMode.wanted()) finish() else update(this)
            }
            MemorySession.frozenCount == 0 && !PlayMode.wanted() -> finish()
            MemorySession.frozenCount == 0 -> {
                // Nothing frozen, but the cheat buttons stay: no wake locks needed for that
                locks?.release()
                locks = null
            }
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
            .setContentTitle(
                if (n > 0) AppText.get(R.string.memory_freeze_notif_title, n)
                else AppText.get(R.string.play_notif_title, MemorySession.cheats.size)
            )
            .setContentText(if (game.isBlank()) AppText.get(R.string.memory_freeze_notif_text_plain) else AppText.get(R.string.memory_freeze_notif_text, game))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, AppText.get(R.string.memory_freeze_notif_stop), stop)
            .apply {
                // Up to two cheats to switch from here (the frozen ones first): Android shows three buttons at most
                quickCheats(MemorySession.cheats).forEachIndexed { i, c ->
                    val toggle = PendingIntent.getService(
                        this@FreezeService, 10 + i,
                        Intent(this@FreezeService, FreezeService::class.java).setAction(ACTION_TOGGLE).putExtra(EXTRA_CHEAT, c.id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(0, AppText.get(if (c.frozen) R.string.freeze_quick_off else R.string.freeze_quick_on, c.name.take(16)), toggle)
                }
            }
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
        const val ACTION_TOGGLE = "com.example.pssender.TOGGLE_FREEZE"
        const val EXTRA_CHEAT = "cheat"

        @Volatile
        private var running = false

        /** Starts the service, or refreshes its notification when it runs already. */
        fun update(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, FreezeService::class.java))
                running = true
            } catch (_: Exception) {
                // Not allowed from the background on some phones: freezing still works while Crimson Multitool is open
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

/** The cheats offered as quick toggles in the freeze notification: the frozen ones first, two at most. */
fun quickCheats(all: List<Cheat>): List<Cheat> = (all.filter { it.frozen } + all.filter { !it.frozen }).take(2)

/**
 * "Cheat buttons in the notification": the freeze notification stays while a game with cheats is connected, even with
 * nothing frozen, so a cheat can be switched on from the notifications without leaving the game.
 */
object PlayMode {
    private const val KEY = "play_mode"

    @Volatile
    var on: Boolean = false
        private set

    fun load(ctx: Context) {
        on = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(ctx: Context, value: Boolean) {
        on = value
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        if (value && MemorySession.cheats.isNotEmpty() && MemorySession.gameConnected) FreezeService.update(ctx)
        if (!value && MemorySession.frozenCount == 0) FreezeService.stop(ctx)
    }

    /** Whether the notification should stay with nothing frozen now. */
    fun wanted(): Boolean = on && MemorySession.cheats.isNotEmpty() && MemorySession.gameConnected
}
