package com.example.pssender // change to match your project's package name

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs a payload chain as a foreground service, so the pauses between the payloads keep their time when the app is not
 * on screen. The chain to run is handed over through [ChainRunner]; progress is published there, too, and in a
 * notification with a Stop button. The service stops itself when the chain is over.
 */
class ChainService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, AppText.get(R.string.notif_channel_chain), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            // From the notification: the chain ends before its next payload
            ChainRunner.requestStop()
            if (!ChainRunner.state.value.active) stopSelf(startId)
            return START_NOT_STICKY
        }
        // Must be called promptly after startForegroundService()
        startForeground(NOTIF_ID, buildNotification(ChainRunner.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val request = ChainRunner.takeRequest()
        if (request == null) {
            // Nothing to do (the request was taken by an earlier start of the service)
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch { work(request) }
        return START_NOT_STICKY
    }

    /** Sends the payloads of [request] and publishes how it went. */
    private suspend fun work(request: ChainRequest) {
        val locks = TransferLocks(this)
        locks.acquire()
        val watcher = scope.launch { watchProgress() }
        val shelf = shelfDir(this)
        try {
            val outcome = try {
                runChain(
                    steps = request.steps,
                    send = { name -> sendPayloadReply(File(shelf, name), request.host, request.port) },
                    sleepSecond = { delay(1000L) },
                    cancelled = { ChainRunner.stopRequested },
                    onStep = ChainRunner::onStep,
                    onWait = ChainRunner::onWait,
                    onRetry = ChainRunner::onRetry
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChainOutcome(request.steps.size, 0, 1, request.steps.firstOrNull()?.payload.orEmpty(), failedText(e))
            }
            ChainRunner.finish(outcome)
        } finally {
            watcher.cancel()
            locks.release()
            mainHandler.post { workEnded() }
        }
    }

    /** Keeps the notification up to date while the chain runs. */
    private suspend fun watchProgress() {
        ChainRunner.state.collect { s ->
            if (s.active) notifyUser(buildNotification(s))
        }
    }

    /** Runs on the main thread when the chain is over: turns the notification into the result (only when the app is hidden) and stops. */
    private fun workEnded() {
        if (ChainRunner.state.value.active) return // a new chain was ordered meanwhile: its start command is on its way
        val s = ChainRunner.state.value
        if (ScreenState.onScreen || s.message.isEmpty()) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
            notifyUser(buildNotification(s))
        }
        stopSelf(lastStartId)
    }

    private fun buildNotification(s: ChainState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val ongoing = s.active
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(s.name.ifEmpty { AppText.get(R.string.nav_payload) })
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            builder.setContentText(chainProgressText(s))
            builder.setProgress(s.steps.coerceAtLeast(1), (s.step - 1).coerceAtLeast(0), false)
            builder.addAction(
                0,
                AppText.get(R.string.action_stop),
                PendingIntent.getService(
                    this, 4,
                    Intent(this, ChainService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            builder.setContentText(s.message)
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    private fun notifyUser(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    override fun onDestroy() {
        // The service going away while a chain is still on the card (the system ended it): the card must not wait for ever
        if (ChainRunner.state.value.active) ChainRunner.failedToStart(AppText.get(R.string.transfer_stopped))
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "chains"
        const val NOTIF_ID = 43
        const val ACTION_STOP = "com.example.pssender.STOP_CHAIN"

        /** Starts the service, which takes the request that [ChainRunner] holds. */
        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, ChainService::class.java))
            } catch (e: Exception) {
                ChainRunner.failedToStart(AppText.get(R.string.transfer_start_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
