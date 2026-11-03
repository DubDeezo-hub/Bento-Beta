package com.example.pssender

// ---------------------------------------------------------------------------------------------
// The update-block check: a system update ends a jailbreak, so many people block the update servers with nanoDNS on the
// console. This reads nanoDNS's own settings files on the console over FTP (on the home network only) and counts the
// rules that block update addresses. It never asks any outside server, and it knows no update address itself: it only
// looks for rules whose name says "update".
// ---------------------------------------------------------------------------------------------

/** What the check found: the settings file it read (empty when none was found) and how many update rules it has. */
data class UpdateBlockResult(val file: String, val rules: Int) {
    val found: Boolean get() = file.isNotEmpty()
    val blocked: Boolean get() = rules > 0
}

/** The rules in a nanoDNS settings [text] that are about updates (comments left out). */
fun countUpdateRules(text: String): Int = text.lineSequence()
    .map { it.substringBefore('#').substringBefore(';').trim() }
    .filter { it.isNotEmpty() && it.contains("update", ignoreCase = true) }
    .count()

/** Whether a file [name] looks like a settings or hosts file (not a payload or a picture). */
fun looksLikeDnsSettings(name: String): Boolean {
    val n = name.lowercase()
    if (n.endsWith(".elf") || n.endsWith(".bin") || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".bak")) return false
    return n.endsWith(".txt") || n.endsWith(".ini") || n.endsWith(".conf") || n.endsWith(".cfg") || n.endsWith(".json") || "hosts" in n || !n.contains('.')
}

object UpdateBlock {
    private const val MAX_FILES = 6
    private const val MAX_BYTES = 128 * 1024

    /** Looks for nanoDNS's settings under /data on the console and reads them. Blocking. */
    fun check(s: FtpSettings): UpdateBlockResult {
        val top = ftpListOrEmpty(s, "/data")
        val candidates = ArrayList<String>()
        for (e in top) {
            if (!e.name.contains("nanodns", ignoreCase = true)) continue
            if (e.isDir) {
                val dir = joinPath("/data", e.name)
                ftpListOrEmpty(s, dir).filter { !it.isDir && looksLikeDnsSettings(it.name) && it.size in 1..MAX_BYTES }
                    .forEach { candidates.add(joinPath(dir, it.name)) }
            } else if (looksLikeDnsSettings(e.name)) {
                candidates.add(joinPath("/data", e.name))
            }
        }
        var best = UpdateBlockResult("", 0)
        ftpReadEach(s, candidates.take(MAX_FILES), MAX_BYTES) { path, data ->
            if (data == null) return@ftpReadEach
            val n = countUpdateRules(String(data, Charsets.UTF_8))
            if (!best.found || n > best.rules) best = UpdateBlockResult(path, n)
        }
        return best
    }
}
