package com.example.pssender

import android.content.Context
import android.content.Intent
import android.net.Uri

// ---------------------------------------------------------------------------------------------
// After a crash: what likely happened, in plain words (from the console's last log lines and what was sent), and a
// bug report for the payload's own developer, filled in with what they need (firmware, the load order with versions,
// the last log lines). Addresses are taken out; the person reads and sends it with their own GitHub account.
// ---------------------------------------------------------------------------------------------

/** One likely cause of a crash. */
enum class CrashCause(val res: Int) {
    FIRMWARE(R.string.expl_firmware),
    TWICE(R.string.expl_twice),
    PANIC(R.string.expl_panic),
    FAULT(R.string.expl_fault),
    MEMORY(R.string.expl_memory),
    SUDDEN(R.string.expl_sudden),
    UNKNOWN(R.string.expl_unknown)
}

private val FIRMWARE_LINE = Regex("""(not|un)\s*supported.*(firmware|fw)|(firmware|fw).*(not|un)\s*supported""", RegexOption.IGNORE_CASE)
private val TWICE_LINE = Regex("""already (loaded|running|been loaded|active)""", RegexOption.IGNORE_CASE)
private val PANIC_LINE = Regex("""\bpanic\b""", RegexOption.IGNORE_CASE)
private val FAULT_LINE = Regex("""page fault|\btrap\b|segfault|sigsegv|general protection|fatal""", RegexOption.IGNORE_CASE)
private val MEMORY_LINE = Regex("""out of memory|enomem|alloc(ation)? fail""", RegexOption.IGNORE_CASE)

/** The likely causes, most telling first, from the console's last [lines] and the payloads [sent] just before. */
fun explainCrash(lines: List<String>, sent: List<String>): List<CrashCause> {
    val out = ArrayList<CrashCause>()
    val text = lines.takeLast(80)
    if (text.any { FIRMWARE_LINE.containsMatchIn(it) }) out.add(CrashCause.FIRMWARE)
    val kinds = sent.mapNotNull { ReloadGuard.kindOf(it) }
    if (text.any { TWICE_LINE.containsMatchIn(it) } || kinds.size != kinds.toSet().size) out.add(CrashCause.TWICE)
    if (text.any { PANIC_LINE.containsMatchIn(it) }) out.add(CrashCause.PANIC)
    if (text.any { FAULT_LINE.containsMatchIn(it) }) out.add(CrashCause.FAULT)
    if (text.any { MEMORY_LINE.containsMatchIn(it) }) out.add(CrashCause.MEMORY)
    // Nothing in the log at all: the console went down too fast to say anything, which a kernel panic does
    if (text.none { it.isNotBlank() && !it.startsWith("#") }) out.add(CrashCause.SUDDEN)
    if (out.isEmpty()) out.add(CrashCause.UNKNOWN)
    return out
}

/** A crash diary note split into the payloads sent before it and the console's last lines. */
data class CrashNoteParts(val sent: List<String>, val lines: List<String>)

fun parseCrashNote(text: String): CrashNoteParts {
    val all = text.lines()
    val sentAt = all.indexOfFirst { it.startsWith("Payloads sent") }
    val linesAt = all.indexOfFirst { it.startsWith("Last lines") }
    val sent = if (sentAt < 0) emptyList() else all.drop(sentAt + 1).takeWhile { it.isNotBlank() }
        .map { it.trim() }.filter { it != "(none)" }.map { it.substringAfterLast("  ").trim() }.filter { it.isNotEmpty() }
    val lines = if (linesAt < 0) emptyList() else all.drop(linesAt + 1)
    return CrashNoteParts(sent, lines)
}

private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

/** [line] without network addresses. */
fun withoutAddresses(line: String): String = IPV4.replace(line, "x.x.x.x")

/** The text of a bug report for [payload]'s developer. */
fun devReportBody(payload: String, version: String, model: String, firmware: String, order: List<String>, lines: List<String>): String = buildString {
    append("**What happened:** the console crashed after loading `").append(payload).append("`.\n\n")
    append("- Payload: ").append(payload).append(if (version.isNotBlank()) " ($version)" else "").append('\n')
    append("- Console: ").append(model).append(", firmware ").append(firmware.ifBlank { "?" }).append('\n')
    if (order.isNotEmpty()) append("- Load order: ").append(order.joinToString(" → ")).append('\n')
    val tail = lines.filter { it.isNotBlank() }.takeLast(20).map { withoutAddresses(it).take(200) }
    if (tail.isNotEmpty()) {
        append("\n**Last console log lines:**\n```\n")
        tail.forEach { append(it).append('\n') }
        append("```\n")
    }
    append("\n_Sent from Crimson Multitool. Network addresses were removed._\n")
}.take(6000)

object DevReport {
    /**
     * Opens a filled-in bug report on the GitHub page of [payload] (known when it came from the payload store). Returns
     * false when the payload's page isn't known.
     */
    fun open(ctx: Context, payload: String, order: List<String>, lines: List<String> = emptyList()): Boolean {
        val repo = Store.keyOf(ctx, payload)?.takeIf { it.count { c -> c == '/' } == 1 } ?: return false
        val version = Store.installedTag(ctx, repo)
        val firmware = ConsoleFirmware.get(ctx, ActiveConsole.id)
        val title = "Crash on ${ActiveConsole.type.model} ${firmware.ifBlank { "" }} after loading $payload".replace("  ", " ")
        val body = devReportBody(payload, version, ActiveConsole.type.model, firmware, order, lines)
        val url = "https://github.com/$repo/issues/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(body)
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}
