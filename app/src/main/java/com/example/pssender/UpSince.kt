package com.example.pssender

import android.content.Context

// ---------------------------------------------------------------------------------------------
// When the console was last jailbroken: the moment its payload loader started answering after it didn't. Home shows how
// long ago that was, so a restart that wiped the jailbreak is plain to see. Crimson Multitool only sees the console while it is open
// (or during the online alert's background look), so when it first finds a console already up it can only say "up for
// at least" since then, unless it saw it up a short while ago and nothing can have happened in between.
// ---------------------------------------------------------------------------------------------

/** Since when the loader answers ([since]), whether that moment was seen ([exact]), and when it was last seen up. */
data class UpState(val since: Long, val exact: Boolean, val lastSeen: Long)

object UpSince {
    /** A gap shorter than this between two looks counts as "still up the whole time". */
    const val GAP_MS = 20L * 60 * 1000

    /**
     * The state after a look at the loader: it was [was] the look before in this run of the app (null for the first
     * look) and is [up] now. [saved] is what was kept from before.
     */
    fun next(saved: UpState?, was: Boolean?, up: Boolean, now: Long): UpState? = when {
        !up -> null
        was == false -> UpState(now, exact = true, lastSeen = now)
        saved != null && now - saved.lastSeen in 0..GAP_MS -> saved.copy(lastSeen = now)
        was == true && saved != null -> saved.copy(lastSeen = now)
        else -> UpState(now, exact = false, lastSeen = now)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("up_since", Context.MODE_PRIVATE)

    fun load(ctx: Context, profileId: String): UpState? {
        val p = prefs(ctx)
        if (!p.contains("since_$profileId")) return null
        return UpState(p.getLong("since_$profileId", 0L), p.getBoolean("exact_$profileId", false), p.getLong("seen_$profileId", 0L))
    }

    /** Works out and keeps the state after a look; gives it. */
    fun update(ctx: Context, profileId: String, was: Boolean?, up: Boolean, now: Long = System.currentTimeMillis()): UpState? {
        val before = load(ctx, profileId)
        val after = next(before, was, up, now)
        if (after != before) {
            val e = prefs(ctx).edit()
            if (after == null) e.remove("since_$profileId").remove("exact_$profileId").remove("seen_$profileId")
            else e.putLong("since_$profileId", after.since).putBoolean("exact_$profileId", after.exact).putLong("seen_$profileId", after.lastSeen)
            e.apply()
        }
        return after
    }

    /** The line Home shows, or "" when the loader doesn't answer. */
    fun text(state: UpState?, now: Long = System.currentTimeMillis()): String {
        if (state == null) return ""
        val ago = fmtDuration(((now - state.since).coerceAtLeast(0L)) / 1000)
        return if (state.exact) {
            AppText.get(R.string.home_up_exact, ago, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(state.since)))
        } else {
            AppText.get(R.string.home_up_atleast, ago)
        }
    }
}
