package com.example.pssender

import android.content.Context

// ---------------------------------------------------------------------------------------------
// The speed limit: an optional cap on how fast transfers go, so the Wi-Fi stays free for other things. The engine asks
// the [Pacer] how long to wait after each piece of data; the setting itself lives in the app's settings.
// ---------------------------------------------------------------------------------------------

/**
 * Keeps a transfer at or under a speed limit. After each piece of data it says how long to wait, so that the average
 * speed stays at the limit. A plain class with no Android parts, so it can be tested on its own. [clock] returns
 * milliseconds.
 */
class Pacer(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var start = 0L
    private var bytes = 0L
    private var forLimit = 0L

    /**
     * Milliseconds to wait now, after [n] more bytes went through, to stay at or under [limit] bytes per second.
     * 0 when there is no limit (zero or less), or when the transfer is slower than the limit anyway. Being slow earns
     * no credit beyond [BURST_MS]: after a stall the transfer does not make up for it at full speed for long.
     */
    @Synchronized
    fun waitMs(n: Long, limit: Long): Long {
        if (limit <= 0L) {
            forLimit = 0L
            bytes = 0L
            return 0L
        }
        val now = clock()
        if (limit != forLimit) {
            // A new limit (or the first piece of data): count from here
            forLimit = limit
            start = now
            bytes = 0L
        }
        bytes += n.coerceAtLeast(0L)
        val due = start + bytes * 1000L / limit // the moment these bytes may be through at the limit
        if (now - due > BURST_MS) {
            start = now
            bytes = 0L
            return 0L
        }
        return if (due > now) due - now else 0L
    }

    companion object {
        /** How far behind the limit a transfer may fall and still go at full speed to catch up. */
        const val BURST_MS = 500L
    }
}

/**
 * The biggest piece of data to move in one go for [len] bytes asked for, under [limit] bytes per second: about an eighth
 * of a second's worth, so the speed stays smooth instead of coming in big bursts. Without a limit it is [len] itself.
 */
fun pieceFor(limit: Long, len: Int): Int {
    if (limit <= 0L || len <= 0) return len
    val piece = (limit / 8L).coerceIn(4096L, 262144L)
    return if (len.toLong() < piece) len else piece.toInt()
}

/** The speed limit setting: which limits the person can pick, and the one that is saved. */
object SpeedLimits {
    private const val KEY = "speed_limit_bps"

    /** The limits to pick from, in bytes per second. 0 means no limit. */
    val choices: List<Long> = listOf(0L, 1L shl 20, 2L shl 20, 5L shl 20, 10L shl 20, 20L shl 20)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The saved limit in bytes per second (0 when there is none). */
    fun load(ctx: Context): Long = prefs(ctx).getLong(KEY, 0L).coerceAtLeast(0L)

    fun save(ctx: Context, bytesPerSecond: Long) {
        prefs(ctx).edit().putLong(KEY, bytesPerSecond.coerceAtLeast(0L)).apply()
    }
}
