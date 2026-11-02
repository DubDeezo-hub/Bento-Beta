package com.example.pssender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

// ---------------------------------------------------------------------------------------------
// "Tell me when it comes online": for a console with the switch on, Bento notices when it starts answering on the
// network (its payload loader or FTP server opens, which is what a jailbreak does) and says so once. While the app is
// open the usual status checks keep track; while it is closed, the phone looks every 15 minutes (the shortest Android
// allows for a background check). Only the console's own address is asked, on your network.
// ---------------------------------------------------------------------------------------------

object OnlineAlert {
    private const val PREFS = "online_alert"
    private const val JOB_ID = 4611
    private const val CHANNEL_ID = "online"
    private const val PERIOD_MS = 15L * 60 * 1000
    private const val PROBE_MS = 1500

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context, profileId: String): Boolean = prefs(ctx).getBoolean("on_$profileId", false)

    /** Turns the alert on or off for one console, and starts or stops the background check to match. */
    fun set(ctx: Context, profileId: String, on: Boolean) {
        // A fresh start: the first look only notes the state, so turning it on never alerts straight away
        prefs(ctx).edit().putBoolean("on_$profileId", on).remove("up_$profileId").apply()
        schedule(ctx)
    }

    /** The state seen last time (null: not looked yet). */
    private fun lastUp(ctx: Context, profileId: String): Boolean? {
        val p = prefs(ctx)
        return if (p.contains("up_$profileId")) p.getBoolean("up_$profileId", false) else null
    }

    /** Whether going from [was] to [up] is the console coming online: only a change from "not answering" counts. */
    fun shouldNotify(was: Boolean?, up: Boolean): Boolean = was == false && up

    /**
     * Notes what was seen for [profile]. With [notify] (the background check), a console that just came online is
     * announced once; the open app only notes the state, since the screen already shows it.
     */
    fun note(ctx: Context, profile: Profile, up: Boolean, notify: Boolean) {
        if (!isOn(ctx, profile.id)) return
        val was = lastUp(ctx, profile.id)
        if (was != up) prefs(ctx).edit().putBoolean("up_${profile.id}", up).apply()
        if (notify && shouldNotify(was, up)) post(ctx, profile)
    }

    /** Whether [profile] answers now: its payload loader or its FTP server. Blocking. */
    fun answers(profile: Profile): Boolean {
        val h = profile.host.trim()
        if (h.isEmpty()) return false
        return listOfNotNull(profile.payloadPort.trim().toIntOrNull(), profile.ftpPort.trim().toIntOrNull()).any { port ->
            Diagnose.probe(h, port, PROBE_MS) == PortState.OPEN
        }
    }

    /** Starts the background check while any console wants it, and stops it when none does. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val wanted = try {
            ProfileStore.load(ctx).any { it.host.isNotBlank() && isOn(ctx, it.id) }
        } catch (_: Exception) {
            false
        }
        if (!wanted) {
            js.cancel(JOB_ID)
            return
        }
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, OnlineJob::class.java))
            .setPeriodic(PERIOD_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        try { js.schedule(job) } catch (_: Exception) {}
    }

    private fun post(ctx: Context, profile: Profile) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, AppText.get(R.string.online_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(AppText.get(R.string.online_title, profile.name))
            .setContentText(AppText.get(R.string.online_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One notification per console, so a second console coming up doesn't replace the first
        nm.notify(5000 + (profile.id.hashCode() and 0xFFF), n)
    }
}

/** The background check: looks at each console with the alert on, and says when one came online. */
class OnlineJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val ctx = applicationContext
        Thread {
            try {
                // AppText is ready: the app's process start (App, Texts.kt) set it up before this job runs
                for (p in ProfileStore.load(ctx)) {
                    if (p.host.isBlank() || !OnlineAlert.isOn(ctx, p.id)) continue
                    OnlineAlert.note(ctx, p, OnlineAlert.answers(p), notify = true)
                }
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
