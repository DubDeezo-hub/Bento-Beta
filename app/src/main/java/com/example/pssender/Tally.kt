package com.example.pssender

// ---------------------------------------------------------------------------------------------
// What a run of transfers amounted to. A run is everything the queue works through from the moment it starts until it
// is empty again. When it held more than one transfer, the screen and the notification say how it went as a whole.
// ---------------------------------------------------------------------------------------------

/** How a run went: how many transfers worked, failed for good, or were stopped; how much was moved; and how long it took. */
data class RunSummary(val done: Int, val failed: Int, val stopped: Int, val bytes: Long, val seconds: Long) {
    /** Every transfer that was started. */
    val total: Int get() = done + failed + stopped

    /** True when every one of them worked. */
    val allGood: Boolean get() = failed == 0 && stopped == 0

    /** A single transfer has its own message; only a run of several gets a summary. */
    val worthShowing: Boolean get() = total >= 2
}

/**
 * Counts the transfers of one run. The time does not include the minutes the run was paused. A plain class with no
 * Android parts, so it can be tested on its own; [clock] returns milliseconds since 1970. Safe to use from any thread.
 */
class RunTally(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var done = 0
    private var failed = 0
    private var stopped = 0
    private var bytes = 0L
    private var startedAt = clock()
    private var pausedTotal = 0L
    private var pausedSince = -1L

    /** A new run begins: everything is counted from zero. */
    @Synchronized
    fun start() {
        done = 0
        failed = 0
        stopped = 0
        bytes = 0L
        startedAt = clock()
        pausedTotal = 0L
        pausedSince = -1L
    }

    /** A transfer worked; [size] is how much it moved. */
    @Synchronized
    fun transferDone(size: Long) {
        done++
        bytes += size.coerceAtLeast(0L)
    }

    /** A transfer failed and was given up on (skipped, or dropped with the queue). */
    @Synchronized
    fun transferFailed() {
        failed++
    }

    /** A transfer was stopped by the person. */
    @Synchronized
    fun transferStopped() {
        stopped++
    }

    @Synchronized
    fun paused() {
        if (pausedSince < 0L) pausedSince = clock()
    }

    @Synchronized
    fun resumed() {
        if (pausedSince >= 0L) {
            pausedTotal += clock() - pausedSince
            pausedSince = -1L
        }
    }

    @Synchronized
    fun summary(): RunSummary {
        val now = clock()
        val pausedNow = if (pausedSince >= 0L) now - pausedSince else 0L
        val ms = (now - startedAt - pausedTotal - pausedNow).coerceAtLeast(0L)
        return RunSummary(done, failed, stopped, bytes, ms / 1000L)
    }
}

/** "Transfers done: 4 of 5": the title of the summary. */
fun summaryTitle(s: RunSummary): String = AppText.get(R.string.summary_title, s.done, s.total)

/** The lines under the title: how much was moved and how long it took, then the transfers that did not work. */
fun summaryDetail(s: RunSummary): String {
    val lines = ArrayList<String>()
    if (s.bytes > 0L) lines.add(AppText.get(R.string.summary_detail, fmtBytes(s.bytes), fmtDuration(s.seconds)))
    val bad = ArrayList<String>()
    if (s.failed > 0) bad.add(AppText.get(R.string.summary_failed, s.failed))
    if (s.stopped > 0) bad.add(AppText.get(R.string.summary_stopped, s.stopped))
    if (bad.isNotEmpty()) lines.add(joinList(bad))
    return lines.joinToString("\n")
}
