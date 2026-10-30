package com.example.pssender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps "Install from this phone" going while the app is in the background or the screen is off: the file server runs
 * here (see [ServeState]), with a notification that has a Stop button, and the processor and the Wi-Fi radio are held
 * awake so the console's download is not slowed down. The service ends when sharing ends.
 */
class ServeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastStartId = 0
    private var lastNotified = 0L

    /** True from the moment this service begins to share until it is over (only touched on the main thread). */
    private var sharing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(TransferService.CHANNEL_ID, AppText.get(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            // From the notification's Stop button
            ServeState.stop()
            if (!sharing) stopSelf(startId) // a leftover button from an earlier run: nothing to stop
            return START_NOT_STICKY
        }
        // Must be called promptly after startForegroundService(), whatever happens next
        startForeground(
            NOTIF_ID,
            buildNotification(ServeState.info.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val request = ServeState.takePending()
        if (request == null) {
            // Nothing to share (the service was started twice): leave quietly
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        sharing = true
        scope.launch { share(request) }
        return START_NOT_STICKY
    }

    private suspend fun share(request: ServeRequest) {
        val locks = TransferLocks(this)
        locks.acquire()
        val watcher = scope.launch { watchProgress() }
        try {
            ServeState.run(request)
        } finally {
            watcher.cancel()
            locks.release()
            mainHandler.post { finished() }
        }
    }

    /** Keeps the notification up to date (at most once a second) while files are shared. */
    private suspend fun watchProgress() {
        ServeState.info.collect { info ->
            if (info.running) {
                val now = System.nanoTime() / 1_000_000L
                if (now - lastNotified >= 1000L) {
                    lastNotified = now
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(info))
                }
            }
        }
    }

    /** Runs on the main thread when sharing is over. */
    private fun finished() {
        sharing = false
        val message = ServeState.info.value.message
        if (message.isEmpty()) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            // Something worth knowing happened (for example the idle stop): keep it as a plain message
            stopForeground(Service.STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ServeState.info.value))
        }
        stopSelf(lastStartId)
    }

    private fun buildNotification(info: ServeInfo): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, TransferService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (info.running) {
            val total = info.files.sumOf { it.size }
            val taken = info.files.sumOf { it.covered }
            val percent = if (total > 0L) (taken * 100 / total).toInt().coerceIn(0, 100) else 0
            val done = info.files.count { it.done }
            builder.setContentTitle(
                if (info.consoleName.isBlank()) AppText.get(R.string.serve_title)
                else AppText.get(R.string.serve_notif_title, info.consoleName)
            )
            builder.setContentText(AppText.get(R.string.serve_notif_text, done, info.files.size))
            builder.setProgress(100, percent, false)
            val extra = speedAndTime(info.speed, info.secondsLeft)
            if (extra.isNotEmpty()) builder.setSubText(extra)
            builder.setOngoing(true)
            val stop = PendingIntent.getService(
                this, 2,
                Intent(this, ServeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, AppText.get(R.string.serve_stop), stop)
        } else {
            builder.setContentTitle(AppText.get(R.string.serve_title))
            builder.setContentText(info.message)
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    override fun onDestroy() {
        // Only when this service is still sharing: a new sharing may already have been prepared for the next one
        if (sharing) ServeState.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // Its own number: transfers use 42 and payload chains 43, and sharing can run at the same time as either
        const val NOTIF_ID = 44
        const val ACTION_STOP = "com.example.pssender.STOP_SHARING"

        /** Starts sharing [request]. Returns false when something is being shared already. */
        fun start(ctx: Context, request: ServeRequest): Boolean {
            if (!ServeState.prepare(request)) return false
            try {
                ctx.startForegroundService(Intent(ctx, ServeService::class.java))
            } catch (e: Exception) {
                ServeState.prepareFailed(AppText.get(R.string.serve_failed, e.message ?: e.javaClass.simpleName))
            }
            return true
        }

        /** Ends sharing. */
        fun stop() {
            ServeState.stop()
        }
    }
}
