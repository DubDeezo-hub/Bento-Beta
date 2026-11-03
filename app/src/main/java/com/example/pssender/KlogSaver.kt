package com.example.pssender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.MediaStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// The console log kept on the phone: while it runs with "Save to phone" on, every line also goes into a text file in
// Downloads/Crimson Multitool/Logs, written out as it arrives. When the console crashes (a kernel panic), the lines just before
// the crash are in that file, and they usually say which payload or game was the cause. A small foreground service
// keeps the app running while the phone is idle (you are playing, the phone is in your pocket).
// ---------------------------------------------------------------------------------------------

/** The app's folder in the phone's Downloads (logs, backups). It was "Bento" before the rename; files there stay where they are. */
const val DOWNLOADS_ROOT = "Crimson Multitool"

/** One log file being written. Lines are written in batches and flushed each time, so nothing waits in memory. */
class KlogFile private constructor(private val out: OutputStream, val shownPath: String) {
    @Synchronized
    fun write(lines: List<String>) {
        try {
            for (l in lines) out.write((l + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        try { out.close() } catch (_: Exception) {}
    }

    companion object {
        const val ROOT = DOWNLOADS_ROOT
        const val FOLDER = "Logs"

        /** "console-log-2026-11-02_14-05-33.txt" */
        fun fileName(now: Date = Date()): String =
            "console-log-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now) + ".txt"

        /** A new log file in Downloads/Crimson Multitool/Logs, starting with which console it is from. Null when the phone refuses. Blocking. */
        fun open(ctx: Context, host: String, port: Int): KlogFile? = try {
            val name = fileName()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, downloadFolder(FOLDER, ROOT))
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            val out = uri?.let { cr.openOutputStream(it, "wa") }
            out?.let {
                KlogFile(it, downloadFolder(FOLDER, ROOT) + "/" + name).also { f ->
                    f.write(listOf("# Crimson Multitool console log: $host port $port, " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())))
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Keeps Crimson Multitool running while the console log is saved to the phone: a notification with a Stop button, Wi-Fi kept awake. */
class KlogService : Service() {
    private var locks: TransferLocks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, AppText.get(R.string.klog_save_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after startForegroundService(), whatever happens next
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        when {
            intent?.action == ACTION_STOP -> {
                KernelLog.stop()
                finish()
            }
            !KernelLog.running && !BlackBox.on -> finish()
            else -> if (locks == null) locks = TransferLocks(this).also { it.acquire() }
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
            this, 6,
            Intent(this, KlogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(AppText.get(R.string.klog_save_notif_title))
            .setContentText(KernelLog.savedTo)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, AppText.get(R.string.klog_stop), stop)
            .build()
    }

    override fun onDestroy() {
        locks?.release()
        locks = null
        super.onDestroy()
    }

    companion object {
        // Its own number: transfers use 42, chains 43, sharing 44, freezing 45
        const val NOTIF_ID = 46
        const val CHANNEL_ID = "console_log"
        const val ACTION_STOP = "com.example.pssender.STOP_CONSOLE_LOG"

        /** Starts the service, or tells it to check whether it is still needed. */
        fun update(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, KlogService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background on some phones: the log is still saved while Crimson Multitool is open
            }
        }
    }
}
