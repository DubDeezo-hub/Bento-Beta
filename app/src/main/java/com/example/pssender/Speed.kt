package com.example.pssender // change to match your project's package name

/**
 * Measures how fast a transfer goes: bytes per second over the last few seconds, and from that the time left.
 * A plain class with no Android parts, so it can be tested on its own. [clock] returns milliseconds.
 */
class SpeedMeter(
    private val windowMs: Long = WINDOW_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private class Sample(val time: Long, val bytes: Long)

    private val samples = ArrayList<Sample>()

    /** Forgets everything and starts again from [done] bytes (used when a transfer starts or resumes). */
    @Synchronized
    fun reset(done: Long) {
        samples.clear()
        samples.add(Sample(clock(), done))
    }

    /** Records that [done] bytes (counted from the start of the file) are through now. */
    @Synchronized
    fun add(done: Long) {
        val now = clock()
        if (samples.isNotEmpty() && done < samples[samples.size - 1].bytes) {
            // The count went backwards (the transfer started over): measure from here
            samples.clear()
        }
        samples.add(Sample(now, done))
        // Only the last few seconds count. The first sample is kept only while it is the last one that is old
        // enough to reach back over the whole window, so the window is never shorter than it should be.
        while (samples.size > 2 && now - samples[1].time >= windowMs) samples.removeAt(0)
    }

    /**
     * Bytes per second over the window, or 0 while it is not known yet (less than a second of data) or nothing
     * moved at all.
     */
    @Synchronized
    fun bytesPerSecond(): Long {
        if (samples.size < 2) return 0L
        val first = samples[0]
        val last = samples[samples.size - 1]
        val span = last.time - first.time
        if (span < MIN_SPAN_MS) return 0L
        val moved = last.bytes - first.bytes
        return if (moved <= 0L) 0L else moved * 1000L / span
    }

    companion object {
        /** How far back the speed looks. Long enough to even out the bumps, short enough to follow a real change. */
        const val WINDOW_MS = 8000L

        /** Less data than this is too little to say anything about the speed. */
        const val MIN_SPAN_MS = 1000L

        /** An estimate longer than this (99 hours) is not shown. */
        const val MAX_SECONDS = 99L * 3600L

        /** Seconds until [total] bytes are through at [speed] bytes per second, or -1 when that can't be told. */
        fun secondsLeft(done: Long, total: Long, speed: Long): Long {
            if (total <= 0L || speed <= 0L || done < 0L) return -1L
            val left = total - done
            if (left <= 0L) return 0L
            val seconds = (left + speed - 1L) / speed
            return if (seconds > MAX_SECONDS) -1L else seconds
        }
    }
}

/** "3.2 MB/s": the speed in the same units as the sizes. */
fun fmtRate(bytesPerSecond: Long): String = fmtBytes(bytesPerSecond) + "/s"

/** 45 seconds -> "0:45", 754 -> "12:34", 3723 -> "1:02:03". */
fun fmtDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
