package com.example.pssender

// ---------------------------------------------------------------------------------------------
// Loading the homebrew enabler (etaHEN, GoldHEN) or the kernel patch (kstuff) a second time while it already runs
// is a common way to crash a console (a kernel panic). Before such a payload goes out, Bento looks whether it was sent
// to this console already in this session, and on a PS5 whether etaHEN already answers (its built-in FTP server on
// port 1337, which is on by default). Then it asks first; the automatic chain doesn't send it at all.
// ---------------------------------------------------------------------------------------------

/** The payloads that must not be loaded twice. */
enum class ReloadKind { ETAHEN, KSTUFF, GOLDHEN }

object ReloadGuard {
    /** etaHEN's built-in FTP server: when it answers, etaHEN runs. */
    const val ETAHEN_FTP_PORT = 1337

    /** A send is remembered this long (after that the console has most likely been restarted). */
    const val REMEMBER_MS = 6L * 60 * 60 * 1000

    private const val PROBE_MS = 800

    /** When each kind was last sent, by console address. */
    private val sent = HashMap<String, HashMap<ReloadKind, Long>>()

    /** Which kind [name] (a payload's file name) is, or null for any other payload. */
    fun kindOf(name: String): ReloadKind? {
        val n = name.lowercase()
        return when {
            "etahen" in n -> ReloadKind.ETAHEN
            "kstuff" in n -> ReloadKind.KSTUFF
            "goldhen" in n -> ReloadKind.GOLDHEN
            else -> null
        }
    }

    /** Notes that [name] went to [host]. */
    fun noteSent(host: String, name: String, now: Long = System.currentTimeMillis()) {
        val kind = kindOf(name) ?: return
        synchronized(sent) { sent.getOrPut(host.trim()) { HashMap() }[kind] = now }
    }

    /** Forgets what was sent (for the tests). */
    fun clear() = synchronized(sent) { sent.clear() }

    /**
     * Why sending [names] (in this order) to [host] could crash the console, or null when nothing speaks against it.
     * Blocking: on a PS5 it tries etaHEN's FTP port ([isOpen] does that; the tests pass their own).
     */
    fun warning(
        host: String,
        names: List<String>,
        type: ConsoleType,
        now: Long = System.currentTimeMillis(),
        isOpen: (String, Int) -> Boolean = { h, p -> Diagnose.probe(h, p, PROBE_MS) == PortState.OPEN }
    ): String? {
        val risky = names.filter { kindOf(it) != null }
        if (risky.isEmpty() || host.isBlank()) return null
        val h = host.trim()
        synchronized(sent) {
            val before = sent[h].orEmpty()
            for (name in risky) {
                val at = before[kindOf(name)] ?: continue
                if (now - at in 0 until REMEMBER_MS) {
                    return AppText.get(R.string.reload_warn_sent, name, fmtDuration((now - at) / 1000))
                }
            }
        }
        if (type == ConsoleType.PS5) {
            val first = risky.firstOrNull { kindOf(it) == ReloadKind.ETAHEN || kindOf(it) == ReloadKind.KSTUFF }
            if (first != null && isOpen(h, ETAHEN_FTP_PORT)) return AppText.get(R.string.reload_warn_running, first, ETAHEN_FTP_PORT)
        }
        return null
    }
}
