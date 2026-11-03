package com.example.pssender

import android.content.Context

// ---------------------------------------------------------------------------------------------
// Which firmware each console runs, and which firmware a payload says it supports. Nothing here is a hard-coded
// compatibility list: the store reads what each payload's own page (its README and release notes) says, so the
// answer stays as current as the authors keep their pages.
// ---------------------------------------------------------------------------------------------

/** The firmware of each saved console, as the debugger reported it or as typed in. */
object ConsoleFirmware {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(ctx: Context, profileId: String): String =
        if (profileId.isEmpty()) "" else prefs(ctx).getString("console_fw_$profileId", "") ?: ""

    fun set(ctx: Context, profileId: String, firmware: String) {
        if (profileId.isEmpty()) return
        val fw = normalize(firmware) ?: return
        prefs(ctx).edit().putString("console_fw_$profileId", fw).apply()
    }

    fun clear(ctx: Context, profileId: String) {
        prefs(ctx).edit().remove("console_fw_$profileId").apply()
    }

    /** "10.40", "10.4", "1040" or "10,40" as "10.40"; null when it is not a firmware number. */
    fun normalize(text: String): String? {
        val t = text.trim().replace(',', '.')
        Regex("""^(\d{1,2})\.(\d{1,2})$""").find(t)?.let { m ->
            val minor = m.groupValues[2].let { if (it.length == 1) it + "0" else it }
            val major = m.groupValues[1].toInt()
            return if (major in 1..FirmwareSupport.MAX_MAJOR) "%d.%s".format(major, minor) else null
        }
        Regex("""^(\d{3,4})$""").find(t)?.let { m ->
            val n = m.value.toInt()
            return if (n / 100 in 1..FirmwareSupport.MAX_MAJOR) "%d.%02d".format(n / 100, n % 100) else null
        }
        return null
    }

    /** "10.40" as 1040. */
    fun number(fw: String): Int? = normalize(fw)?.let { val (a, b) = it.split('.'); a.toInt() * 100 + b.toInt() }
}

/** How a payload fits a console's firmware. */
enum class FwFit {
    /** Its page names this firmware (or a range or family that holds it). */
    LISTED,

    /** Its page names firmware, but not this one. */
    NOT_LISTED,

    /** Its page says nothing clear about firmware. */
    UNKNOWN,

    /** A plain tool that runs wherever payloads can be loaded. */
    ANY
}

/** What a payload's page says about firmware: the versions, ranges and families (like 10.xx) it names. */
class FwClaims(val exact: Set<Int>, val ranges: List<IntRange>) {
    val isEmpty get() = exact.isEmpty() && ranges.isEmpty()

    /** The lowest and highest firmware it names, like "3.00" and "13.60". */
    val lowest: String? get() = named().minOrNull()?.let(::text)
    val highest: String? get() = named().maxOrNull()?.let(::text)
    private fun named(): Collection<Int> = if (exactOnly) exact else exact + ranges.flatMap { listOf(it.first, it.last) }

    /**
     * A long list of exact versions is the real list; a range next to it ("37 versions across 5.05-13.52") is only a
     * summary of it, and a family row label ("10.xx | 10.00, 10.01") is only a heading.
     */
    private val exactOnly get() = exact.size >= FirmwareSupport.LIST_IS_EXACT

    fun fit(firmware: Int): FwFit = when {
        isEmpty -> FwFit.UNKNOWN
        firmware in exact -> FwFit.LISTED
        !exactOnly && ranges.any { firmware in it } -> FwFit.LISTED
        else -> FwFit.NOT_LISTED
    }

    companion object {
        fun text(n: Int): String = if (n >= FirmwareSupport.OPEN_END) "∞" else "%d.%02d".format(n / 100, n % 100)
    }
}

object FirmwareSupport {
    /** Consoles have not gone past this major firmware number; anything higher is not a firmware version. */
    const val MAX_MAJOR = 30

    /** From this many exact versions on, a page's list is taken as the whole list. */
    const val LIST_IS_EXACT = 6

    /** The top of an open range ("9.00 and later"). */
    const val OPEN_END = 9999

    /** A version: 10.40, or a family: 10.xx / 10.x. Not part of a longer number (7.01.01, 22.04.1) or a "v1.02" tag. */
    private const val V = """(?<![\w.])(\d{1,2})\.(\d{2}|[xX]{1,2})(?![\w]|\.\d)"""
    private val VERSION = Regex(V)
    private val RANGE = Regex("""$V\s*(?:-|–|—|~|to|through|thru)\s*$V""", RegexOption.IGNORE_CASE)
    private val UP_TO = Regex("""(?:up\s*to|<=|≤|until|below|before)\s*(?:fw|firmware)?\s*$V""", RegexOption.IGNORE_CASE)
    private val AND_BELOW = Regex("""$V\s*(?:and|or|&)\s*(?:below|lower|earlier|under|older)""", RegexOption.IGNORE_CASE)
    private val AND_ABOVE = Regex("""$V\s*(?:\+|(?:and|or|&)\s*(?:above|higher|later|newer|up))""", RegexOption.IGNORE_CASE)
    private val FROM = Regex("""(?:>=|≥|from)\s*(?:fw|firmware)?\s*$V\s*(?:\+|onwards|on\b)""", RegexOption.IGNORE_CASE)
    private val TOPIC = Regex("""firmware|\bo?fws?\b|support|compatib|works on|tested""", RegexOption.IGNORE_CASE)
    private val NEGATIVE = Regex("""not\s+(?:yet\s+)?support|unsupported|doesn'?t\s+work|does\s+not\s+work|won'?t\s+work|broken|not\s+working""", RegexOption.IGNORE_CASE)

    /** The lines of a page worth reading for firmware: those about firmware or support, and lists of versions. */
    fun relevantLines(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length < 400 && !it.contains("://") }
        .filter { line -> VERSION.findAll(line).count().let { n -> n >= 2 || (n >= 1 && TOPIC.containsMatchIn(line)) } }
        .toList()

    /** What [text] (a README, release notes, or both) claims about firmware. */
    fun claims(text: String): FwClaims {
        val exact = HashSet<Int>()
        val ranges = ArrayList<IntRange>()
        // A stated range says more than a family heading ("2.xx") on its own
        var weight = 0
        for (raw in relevantLines(text)) {
            if (NEGATIVE.containsMatchIn(raw)) continue
            var line = raw
            fun take(r: Regex, make: (MatchResult) -> IntRange?) {
                line = r.replace(line) { m ->
                    make(m)?.let { ranges.add(it); weight += 2 }
                    " "
                }
            }
            take(RANGE) { m -> span(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) }
            take(UP_TO) { m -> family(m.groupValues[1], m.groupValues[2])?.let { 100..it.last } }
            take(AND_BELOW) { m -> family(m.groupValues[1], m.groupValues[2])?.let { 100..it.last } }
            take(AND_ABOVE) { m -> family(m.groupValues[1], m.groupValues[2])?.let { it.first..OPEN_END } }
            take(FROM) { m -> family(m.groupValues[1], m.groupValues[2])?.let { it.first..OPEN_END } }
            VERSION.findAll(line).forEach { m ->
                val r = family(m.groupValues[1], m.groupValues[2]) ?: return@forEach
                if (r.first == r.last) exact.add(r.first) else ranges.add(r)
                weight++
            }
        }
        // One stray mention ("decryption only on 2.xx") is not a statement of what it supports
        return if (weight < 2) FwClaims(emptySet(), emptyList()) else FwClaims(exact, ranges)
    }

    /** 10 + "40" as 1040..1040; 10 + "xx" as 1000..1099. Null when it is not a plausible firmware. */
    private fun family(major: String, minor: String): IntRange? {
        val a = major.toIntOrNull() ?: return null
        if (a !in 1..MAX_MAJOR) return null
        if (minor.startsWith("x", true)) return a * 100..a * 100 + 99
        val b = minor.toIntOrNull() ?: return null
        return (a * 100 + b).let { it..it }
    }

    private fun span(a1: String, b1: String, a2: String, b2: String): IntRange? {
        val from = family(a1, b1) ?: return null
        val to = family(a2, b2) ?: return null
        return if (from.first <= to.last) from.first..to.last else null
    }
}

// ---------------------------------------------------------------------------------------------
// The firmware check before sending: what a payload's page said about firmware is kept when it comes from the store,
// so sending it to a console on firmware the page doesn't name asks first ("its page lists 4.03 to 10.01; your PS5 is on
// 10.40"). Payloads from elsewhere, and pages that say nothing clear, send without asking.
// ---------------------------------------------------------------------------------------------

object PayloadFirmware {
    private const val PREFS = "payload_fw"

    /** The kept claims, by shelf file name (loaded once at the start, then kept in step). */
    private val kept = java.util.concurrent.ConcurrentHashMap<String, FwClaims>()

    /** The selected console's firmware as a number (1040 for 10.40), or null when not known. */
    @Volatile
    var consoleFirmware: Int? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        kept.clear()
        for ((name, v) in p.all) decode(v as? String ?: continue)?.let { kept[name] = it }
    }

    /** Keeps what [name]'s page says (nothing clear: forgets it, so it sends without asking). */
    fun remember(ctx: Context, name: String, claims: FwClaims?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (claims == null || claims.isEmpty) {
            kept.remove(name)
            p.remove(name)
        } else {
            kept[name] = claims
            p.putString(name, encode(claims))
        }
        p.apply()
    }

    fun forget(ctx: Context, name: String) = remember(ctx, name, null)

    /** The question to ask before sending [names] (the first one whose page doesn't name the console's firmware), or null. */
    fun warning(names: List<String>, firmware: Int? = consoleFirmware, model: String = ActiveConsole.type.model): String? {
        val fw = firmware ?: return null
        for (name in names) {
            val c = kept[name] ?: continue
            if (c.fit(fw) == FwFit.NOT_LISTED) {
                return AppText.get(R.string.fwcheck_warn, name, c.lowest ?: "?", c.highest ?: "?", model, FwClaims.text(fw))
            }
        }
        return null
    }

    /** For the tests: claims as they would be kept. */
    internal fun put(name: String, claims: FwClaims) {
        kept[name] = claims
    }

    internal fun reset() {
        kept.clear()
        consoleFirmware = null
    }

    /** "e:1040,1050;r:403-1001,1100-1252" */
    fun encode(c: FwClaims): String =
        "e:" + c.exact.sorted().joinToString(",") + ";r:" + c.ranges.joinToString(",") { "${it.first}-${it.last}" }

    fun decode(text: String): FwClaims? {
        val parts = text.split(';')
        val e = parts.firstOrNull { it.startsWith("e:") }?.removePrefix("e:") ?: return null
        val r = parts.firstOrNull { it.startsWith("r:") }?.removePrefix("r:") ?: return null
        val exact = e.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        val ranges = r.split(',').mapNotNull { span ->
            val a = span.substringBefore('-', "").toIntOrNull()
            val b = span.substringAfter('-', "").toIntOrNull()
            if (a != null && b != null && a <= b) a..b else null
        }
        val c = FwClaims(exact, ranges)
        return if (c.isEmpty) null else c
    }
}
