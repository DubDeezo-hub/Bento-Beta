package com.example.pssender

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// When Crimson Multitool closes because of a mistake in the app (a crash), what went wrong is written to a small file before it
// closes. The next time it opens, it says so and offers to share the report (to send to whoever helps you, or to
// paste into a bug report). Only the app's version, the phone's model and Android version, and the error are in it:
// no files, addresses or settings.
// ---------------------------------------------------------------------------------------------

object CrashReports {
    private const val FILE = "last-crash.txt"

    /** The report text is cut to this many characters (an error with a huge cause chain stays readable). */
    private const val MAX_CHARS = 20_000

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Starts noting crashes. The phone's own handling (closing the app) still happens afterwards. */
    fun install(ctx: Context, versionName: String) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, versionName, previous))
    }

    private class Handler(
        val ctx: Context,
        val versionName: String,
        val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                file(ctx).writeText(report(versionName, t.name, e))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The text of a report: when, which version, which phone, and the error with where it happened. */
    fun report(versionName: String, thread: String, e: Throwable, now: Date = Date()): String {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            append("Crimson Multitool crash report\n")
            append("When: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)).append('\n')
            append("Version: ").append(versionName).append('\n')
            append("Phone: ").append(Build.MANUFACTURER ?: "?").append(' ').append(Build.MODEL ?: "?")
                .append(", Android ").append(Build.VERSION.RELEASE ?: "?").append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Thread: ").append(thread).append("\n\n")
            append(trace)
        }
        return text.take(MAX_CHARS)
    }

    /** The report from the last crash, or null when the app didn't crash since it was last looked at. */
    fun pending(ctx: Context): String? = try {
        file(ctx).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Forgets the report (after it was shared or put away). */
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
