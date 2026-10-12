package com.example.pssender // change to match your project's package name

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The longest the phone is kept awake for one run of transfers (a safety net: the locks are normally let go much earlier). */
internal const val LOCK_MAX_MS = 12L * 60L * 60L * 1000L

/**
 * Keeps the processor and the Wi-Fi radio awake while transfers run. Without them Android may slow the Wi-Fi down or
 * pause the app a few minutes after the screen goes off, which makes a long transfer crawl or drop.
 */
internal class TransferLocks(ctx: Context) {
    private val wake: PowerManager.WakeLock? = try {
        ctx.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pssender:transfer")
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private val wifi: WifiManager.WifiLock? = try {
        ctx.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pssender:transfer")
    } catch (_: Exception) {
        null
    }

    fun acquire() {
        try {
            wake?.acquire(LOCK_MAX_MS)
        } catch (_: Exception) {
        }
        try {
            wifi?.acquire()
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            if (wake?.isHeld == true) wake.release()
        } catch (_: Exception) {
        }
        try {
            if (wifi?.isHeld == true) wifi.release()
        } catch (_: Exception) {
        }
    }
}

/**
 * Works through the queue of FTP transfers (uploads and downloads) as a foreground service, so it keeps going when the
 * app is in the background or the screen is off. Progress is published through [TransferState] and a notification with
 * Pause and Cancel buttons. The service runs while there is something to do and stops itself when the queue is empty or
 * waits for the user after a failed transfer. While the user has paused, it lets go of the wake locks and connections
 * and waits; the transfer then continues from where it stopped.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastStartId = 0
    private var lastNotified = 0L
    private var lastPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        // Progress is quiet; the result of a run (done, or failed and waiting for you) may make a sound when the app is hidden
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, AppText.get(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, AppText.get(R.string.notif_channel_results), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                // From the notification: everything stops, not only the running transfer
                TransferState.clearQueue()
                TransferState.requestCancel()
                if (!TransferState.queue.isWorking) stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_PAUSE, ACTION_RESUME -> {
                // From the notification: hold the running transfer, or let it carry on
                if (intent?.action == ACTION_PAUSE) TransferState.pauseTransfer() else TransferState.resumeTransfer()
                if (!TransferState.queue.isWorking) stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        // Must be called promptly after startForegroundService()
        startForeground(
            NOTIF_ID,
            buildNotification(TransferState.info.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        // One worker for each time the queue asked for the service to be started
        scope.launch { work() }
        return START_NOT_STICKY
    }

    /** Runs the queued transfers one after the other until none is left or one fails and the queue has to wait. */
    private suspend fun work() {
        val locks = TransferLocks(this)
        locks.acquire()
        val watcher = scope.launch { watchProgress() }
        try {
            while (true) {
                val entry = TransferState.takeNext() ?: break
                TransferState.begin(entry)
                // While the user has paused, the phone is let go of (no wake locks, no connections) until it is resumed
                val result = runAcrossPauses(TransferState, locks::release, locks::acquire) { runTransfer(entry) }
                if (!TransferState.finishItem(entry, result)) break
            }
        } finally {
            watcher.cancel()
            locks.release()
            mainHandler.post { workerEnded() }
        }
    }

    private fun runTransfer(entry: QueueEntry<TransferRequest>): TransferResult {
        val req = entry.item
        val memory = TransferState.memoryFor(entry.id)
        val settings = FtpSettings(req.host, req.port, req.user, req.pass, req.model)
        return when {
            req.remotePath != null ->
                ftpDownloadToPhone(contentResolver, req.remotePath, req.name, req.remoteSize, settings, memory, TransferState, req.dir)
            req.fileUri != null ->
                ftpUploadPicked(contentResolver, Uri.parse(req.fileUri), req.name, settings, req.dir, memory, TransferState)
            else ->
                ftpUploadFromUrl(
                    req.url.orEmpty(), req.userAgent ?: FALLBACK_UA, req.cookie, req.name, settings, req.dir,
                    memory, TransferState
                )
        }
    }

    /** Keeps the notification up to date (at most once a second) while a transfer runs. */
    private suspend fun watchProgress() {
        TransferState.info.collect { t ->
            if (t.active) {
                val now = System.nanoTime() / 1_000_000L
                // Once a second at most, but a pause or a resume shows at once (nothing else would update it after a pause)
                if (now - lastNotified >= 1000L || t.paused != lastPaused) {
                    lastNotified = now
                    lastPaused = t.paused
                    notifyUser(buildNotification(t))
                }
            }
        }
    }

    /** Runs on the main thread after a worker is done. Stops the service unless a new worker was ordered meanwhile. */
    private fun workerEnded() {
        if (TransferState.queue.isWorking) return // a new start command is on its way
        // Keep the notification, but turn it into a plain result message that can be swiped away
        stopForeground(Service.STOP_FOREGROUND_DETACH)
        notifyUser(buildNotification(TransferState.info.value))
        stopSelf(lastStartId)
    }

    /** The small line under the title: speed, time left and how many transfers wait behind this one. */
    private fun subLine(t: TransferInfo): String {
        val parts = ArrayList<String>()
        val speed = speedAndTime(t)
        if (speed.isNotEmpty()) parts.add(speed)
        val waiting = TransferState.queue.waitingCount
        if (waiting > 0) parts.add(AppText.get(R.string.transfer_queued, waiting))
        return joinList(parts)
    }

    private fun buildNotification(t: TransferInfo): Notification {
        val ongoing = t.active
        val name = t.label.ifEmpty { AppText.get(R.string.transfer_default_name) }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        // The result of a run is announced on its own channel, and only when the app is hidden (on screen it buzzes instead)
        val builder = Notification.Builder(this, if (ongoing) CHANNEL_ID else CHANNEL_RESULTS)
            .setSmallIcon(
                when {
                    t.download && ongoing -> android.R.drawable.stat_sys_download
                    t.download -> android.R.drawable.stat_sys_download_done
                    ongoing -> android.R.drawable.stat_sys_upload
                    else -> android.R.drawable.stat_sys_upload_done
                }
            )
            .setContentTitle(name)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing || ScreenState.onScreen)
        if (ongoing) {
            val unknown = t.total <= 0L
            val percent = if (unknown) 0 else (t.done * 100 / t.total).toInt().coerceIn(0, 100)
            builder.setContentText(
                when {
                    t.paused -> pausedText(t)
                    t.status.isNotEmpty() -> t.status
                    !unknown -> AppText.get(
                        R.string.transfer_detail_total,
                        AppText.get(R.string.percent_value, percent), fmtBytes(t.done), fmtBytes(t.total)
                    )
                    t.download -> AppText.get(R.string.notif_received, fmtBytes(t.done))
                    else -> AppText.get(R.string.notif_sent, fmtBytes(t.done))
                }
            )
            builder.setProgress(100, percent, unknown && !t.paused)
            val extra = subLine(t)
            if (extra.isNotEmpty()) builder.setSubText(extra)
            builder.addAction(
                0,
                AppText.get(if (t.paused) R.string.transfer_resume else R.string.transfer_pause),
                actionIntent(if (t.paused) 3 else 2, if (t.paused) ACTION_RESUME else ACTION_PAUSE)
            )
            val label = if (TransferState.queue.waitingCount > 0) R.string.action_cancel_all else R.string.action_cancel
            builder.addAction(0, AppText.get(label), actionIntent(1, ACTION_CANCEL))
        } else {
            builder.setContentText(t.message)
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    /** The line for a held transfer: "Paused · 1.2 GB of 2.8 GB". */
    private fun pausedText(t: TransferInfo): String {
        val paused = AppText.get(R.string.transfer_paused)
        return when {
            t.total > 0L -> AppText.get(R.string.transfer_detail_total, paused, fmtBytes(t.done), fmtBytes(t.total))
            t.done > 0L -> AppText.get(R.string.transfer_detail_done, paused, fmtBytes(t.done))
            else -> paused
        }
    }

    /** What a button of the notification does: sends [action] back to this service. [code] tells the buttons apart. */
    private fun actionIntent(code: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, TransferService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun notifyUser(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    override fun onDestroy() {
        TransferState.stopped()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "transfers"
        const val CHANNEL_RESULTS = "transfer_results"
        const val NOTIF_ID = 42
        const val ACTION_CANCEL = "com.example.pssender.CANCEL"
        const val ACTION_PAUSE = "com.example.pssender.PAUSE"
        const val ACTION_RESUME = "com.example.pssender.RESUME"

        /**
         * Puts a transfer in the queue and starts the service if it isn't busy. Progress shows in [TransferState] and in a
         * notification. Returns true when the transfer starts right away, false when it has to wait its turn.
         */
        fun enqueue(ctx: Context, req: TransferRequest): Boolean {
            val startsNow = TransferState.enqueue(req)
            if (startsNow) startWorker(ctx)
            return startsNow
        }

        /** Tries the failed transfer again and goes on with the queue. */
        fun retry(ctx: Context) {
            if (TransferState.retry()) startWorker(ctx)
        }

        /** Drops the failed transfer and goes on with the queue. */
        fun skip(ctx: Context) {
            if (TransferState.skip()) startWorker(ctx)
        }

        /**
         * Puts the transfers that the last run of the app left behind into the queue (see
         * [TransferCenter.continueUnfinished]) and starts the service if it isn't busy.
         */
        fun continueUnfinished(
            ctx: Context,
            usable: (TransferRequest) -> Boolean,
            refresh: (TransferRequest) -> TransferRequest
        ): ContinueResult {
            val result = TransferState.continueUnfinished(usable, refresh)
            if (result.start) startWorker(ctx)
            return result
        }

        private fun startWorker(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, TransferService::class.java))
            } catch (e: Exception) {
                TransferState.workerFailedToStart(AppText.get(R.string.transfer_start_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
