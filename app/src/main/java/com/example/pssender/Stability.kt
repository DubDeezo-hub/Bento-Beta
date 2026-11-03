package com.example.pssender

import android.content.Context

// ---------------------------------------------------------------------------------------------
// The stability report: for each payload, how often it was loaded and how often the console went down (the crash diary's
// "went silent") within 10 minutes of it. A payload that is there before most crashes stands out, which is the pattern
// to look for with kernel panics. Counts stay on the phone.
// ---------------------------------------------------------------------------------------------

/** One payload in the report: [loads] times sent, [crashes] times the console went down soon after. */
data class StabilityRow(val name: String, val loads: Int, val crashes: Int) {
    /** Crashes as a share of loads, 0 to 100. */
    val percent: Int get() = if (loads <= 0) 0 else (crashes * 100 / loads).coerceAtMost(100)

    /** Worth a warning: at least two crashes, after at least half of its loads. */
    val suspect: Boolean get() = crashes >= 2 && crashes * 2 >= loads
}

/** The report from the counts: the suspects first, then the most crashes for the loads. */
fun stabilityRows(loads: Map<String, Int>, crashes: Map<String, Int>): List<StabilityRow> =
    (loads.keys + crashes.keys).map { n ->
        val c = crashes[n] ?: 0
        StabilityRow(n, maxOf(loads[n] ?: 0, c), c)
    }.sortedWith(
        compareByDescending<StabilityRow> { it.suspect }
            .thenByDescending { it.percent }
            .thenByDescending { it.crashes }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )

object Stability {
    private const val PREFS = "stability"
    private const val LOAD = "l:"
    private const val CRASH = "c:"

    @Volatile
    private var app: Context? = null

    /** Called once when the app's process starts. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [name] was sent to a console. */
    fun noteLoad(name: String) = bump(listOf(LOAD + name))

    /** A console went down; [names] went to it in the minutes before. */
    fun noteCrash(names: Collection<String>) = bump(names.distinct().map { CRASH + it })

    private fun bump(keys: List<String>) {
        val p = prefs() ?: return
        if (keys.isEmpty()) return
        synchronized(this) {
            val e = p.edit()
            for (k in keys) e.putInt(k, p.getInt(k, 0) + 1)
            e.apply()
        }
    }

    fun rows(ctx: Context): List<StabilityRow> {
        val all = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        fun counts(prefix: String) = all.filterKeys { it.startsWith(prefix) }
            .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(prefix) to it } }.toMap()
        return stabilityRows(counts(LOAD), counts(CRASH))
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
