package com.example.pssender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

// ---------------------------------------------------------------------------------------------
// Telling the person, in the phone's notifications and in the app, when a download from the web starts on its way to
// the console and when it has arrived (or didn't), and when a link went to the console's installer. One notification per
// file: "started" turns into "sent" (or "didn't reach") in place, so the shade doesn't fill up.
// ---------------------------------------------------------------------------------------------

/** What the app shows in its status line about a web download (the newest last). */
data class ConsoleNotice(val text: String, val ok: Boolean, val at: Long = System.currentTimeMillis())

object ConsoleNotices {
    /** The newest notice, for the status line in the app (read by MainActivity). */
    val latest = MutableStateFlow<ConsoleNotice?>(null)

    /** The notification of one file: the same one is updated from "started" to "sent". */
    fun idFor(name: String): Int = 7000 + (name.hashCode() and 0x0FFF)

    /** A download from the web has started on its way to the console. */
    fun started(ctx: Context, name: String, model: String, dir: String) {
        val text = AppText.get(R.string.notice_dl_started_text, name, model, dir)
        latest.value = ConsoleNotice(text, ok = true)
        post(ctx, idFor(name), AppText.get(R.string.notice_dl_started_title), text, ongoing = true)
    }

    /** It arrived on the console ([ok]), or didn't ([problem] says why). A stop by hand only clears the notice. */
    fun finished(ctx: Context, name: String, model: String, dir: String, ok: Boolean, problem: String, cancelled: Boolean) {
        if (cancelled) {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(idFor(name))
            return
        }
        val title = AppText.get(if (ok) R.string.notice_dl_done_title else R.string.notice_dl_failed_title, model)
        val text = if (ok) AppText.get(R.string.notice_dl_done_text, name, model, dir) else "$name: $problem"
        latest.value = ConsoleNotice(text, ok)
        post(ctx, idFor(name), title, text, ongoing = false)
    }

    /** A link went to the console's installer and it accepted it. */
    fun installSent(ctx: Context, name: String, model: String, reply: String) {
        latest.value = ConsoleNotice(reply, ok = true)
        post(ctx, idFor(name), AppText.get(R.string.notice_install_title, model), "$name: $reply", ongoing = false)
    }

    private fun post(ctx: Context, id: Int, title: String, text: String, ongoing: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // The same channel as the transfer results, so the person sets these up in one place
        nm.createNotificationChannel(
            NotificationChannel(TransferService.CHANNEL_RESULTS, AppText.get(R.string.notif_channel_results), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, TransferService.CHANNEL_RESULTS)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(!ongoing)
            .build()
        nm.notify(id, n)
    }
}
