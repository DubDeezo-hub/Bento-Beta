package com.example.pssender

// ---------------------------------------------------------------------------------------------
// Finding the console by itself: home routers often give a console a new address after a restart. When the saved address
// doesn't answer at the app's start, the app looks on the Wi-Fi network (the same look as "Find my PS5", only on the
// home network) and, when exactly one other console answers, offers its address. Nothing changes without a tap.
// ---------------------------------------------------------------------------------------------

/**
 * The one console address worth offering from [hits]: not the [saved] one, not one saved for another console ([others]).
 * Null when none, or when more than one could be it (then the person picks in "Find my PS5").
 */
fun autoPick(hits: List<ScanHit>, saved: String, others: Set<String>): String? {
    // Only addresses where the loader or installer answered: an FTP server alone could be any device (a NAS, a printer)
    val candidates = hits.filter { it.strong }.map { it.host }.distinct().filter { it != saved.trim() && it !in others }
    return candidates.singleOrNull()
}

/** What the app's start found: [found] answers where [saved] didn't. */
data class AutoFound(val profileId: String, val saved: String, val found: String)

/** How long after the start the look waits, so the app's own first checks go first. */
const val AUTO_FIND_DELAY_MS = 4000L
