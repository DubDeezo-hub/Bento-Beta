package com.example.pssender

import android.content.Context
import org.json.JSONArray
import java.net.URLDecoder

// ---------------------------------------------------------------------------------------------
// Web links in the Install box: finding them in pasted text, sending several in a row, and the
// list of recent links.
// ---------------------------------------------------------------------------------------------

/** The most links sent in one go (a pasted page of text could hold hundreds). */
const val LINKS_MAX = 50

/** How many recent links are kept. */
const val RECENT_MAX = 10

/** A web address inside some text. It ends at a space, a quote or an angle bracket. */
private val LINK_IN_TEXT_RE = Regex("""https?://[^\s\u00a0<>"']+""", RegexOption.IGNORE_CASE)

/** Spaces, line breaks and the no-break space that some chat apps put between words. */
private val SPACES_RE = Regex("""[\s\u00a0]+""")

/** Marks that open a link in a sentence: "(https://...)" or "<https://...>". */
private const val OPENERS = "([{<\"'`"

/** Marks that end a sentence after a link and don't belong to it. */
private const val SENTENCE_END = ".,;:!?'\"`>"

/** [raw] without the marks around it that belong to the sentence it was found in. */
fun cleanLink(raw: String): String {
    var s = raw.trim().trimStart { it in OPENERS }
    while (s.isNotEmpty()) {
        val last = s.last()
        val drop = when {
            last in SENTENCE_END -> true
            // a closing bracket belongs to the link only when the link has the opening one too
            last == ')' -> s.count { it == '(' } < s.count { it == ')' }
            last == ']' -> s.count { it == '[' } < s.count { it == ']' }
            last == '}' -> s.count { it == '{' } < s.count { it == '}' }
            else -> false
        }
        if (!drop) break
        s = s.dropLast(1)
    }
    return s
}

private val WEB_LINK_RE = Regex("""^https?://[^\s/?#]+\S*$""", RegexOption.IGNORE_CASE)

/** True when [s] is a web address with a host: http:// or https:// and something after it. */
fun isWebLink(s: String): Boolean = WEB_LINK_RE.matches(s)

/** The web links in [text], in the order they appear, each once, at most [LINKS_MAX]. Everything else is ignored. */
fun linksIn(text: String): List<String> {
    val found = LinkedHashSet<String>()
    for (m in LINK_IN_TEXT_RE.findAll(text)) {
        val link = cleanLink(m.value)
        if (isWebLink(link)) found.add(link)
        if (found.size >= LINKS_MAX) break
    }
    return found.toList()
}

/** The words of [text] that hold no web link: what the Install box leaves out when sending. */
fun skippedWords(text: String): List<String> =
    text.trim().split(SPACES_RE).filter { it.isNotEmpty() && linksIn(it).isEmpty() }

/** [current] with the links of [add] put at the end, one per line. A link that is in it already isn't added again. */
fun mergeLinks(current: String, add: List<String>): String {
    val have = linksIn(current).toSet()
    val fresh = add.filter { it !in have }.distinct()
    if (fresh.isEmpty()) return current
    val base = current.trimEnd()
    return if (base.isEmpty()) fresh.joinToString("\n") else base + "\n" + fresh.joinToString("\n")
}

/** [text] without the words that hold only links from [done]. The other words and lines stay as they are. */
fun removeLinks(text: String, done: Set<String>): String =
    text.lines().mapNotNull { line ->
        val kept = line.trim().split(SPACES_RE).filter { word ->
            val inWord = linksIn(word)
            word.isNotEmpty() && !(inWord.isNotEmpty() && inWord.all { it in done })
        }
        if (kept.isEmpty()) null else kept.joinToString(" ")
    }.joinToString("\n")

/** A short name for a link, for a button: the file name at its end, or the website when there is none. */
fun linkLabel(url: String, max: Int = 28): String {
    val noExtras = url.substringBefore('#').substringBefore('?')
    val afterScheme = noExtras.substringAfter("://")
    val host = afterScheme.substringBefore('/')
    val last = afterScheme.substringAfter('/', "").trimEnd('/').substringAfterLast('/')
    val name = if (last.isNotEmpty()) {
        try {
            URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            last
        }
    } else {
        host
    }
    return if (name.length > max) name.take(max - 1) + "…" else name
}

// ---------------------------------------------------------------------------------------------
// Recent links
// ---------------------------------------------------------------------------------------------

/** [list] with [link] at the front (it moves there when it was in the list already), at most [max] long. */
fun pushRecent(list: List<String>, link: String, max: Int = RECENT_MAX): List<String> =
    (listOf(link) + list.filter { it != link }).take(max)

fun recentToJson(list: List<String>): String = JSONArray(list).toString()

/** Reads the saved list. Anything that isn't a web link is dropped; broken input gives an empty list. */
fun parseRecent(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.optString(it, "") }.filter { isWebLink(it) }.distinct().take(RECENT_MAX)
    } catch (_: Exception) {
        emptyList()
    }
}

/** The links that were accepted by the console's installer, newest first. They stay on the phone, in the app's settings. */
object RecentLinkStore {
    private const val KEY = "recent_links_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<String> = parseRecent(prefs(ctx).getString(KEY, null))

    /** Puts [links] in front, keeping their order (the first one ends up newest). Returns the new list. */
    fun push(ctx: Context, links: List<String>): List<String> {
        var list = load(ctx)
        for (link in links.asReversed()) list = pushRecent(list, link)
        prefs(ctx).edit().putString(KEY, recentToJson(list)).apply()
        return list
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}

// ---------------------------------------------------------------------------------------------
// Sending several links
// ---------------------------------------------------------------------------------------------

/**
 * What sending a list of links gave: the [accepted] ones, the [failed] ones with the message of each, and the [notSent]
 * ones that were never tried (the person stopped it, or the console could not be reached). [lastOkText] is the
 * installer's own answer to the last accepted link.
 */
class InstallBatch(
    val accepted: List<String>,
    val failed: List<Pair<String, String>>,
    val notSent: List<String>,
    val lastOkText: String
)

/** True when [text] is the message of a problem that every next link would run into too: the console can't be reached. */
fun isConsoleGone(text: String): Boolean {
    val topic = Hints.topicOf(text)
    return topic == HelpTopic.UNREACHABLE || topic == HelpTopic.PORT_CLOSED || topic == HelpTopic.DROPPED
}

/**
 * Sends [links] one after the other with [send]. A link the installer doesn't accept doesn't stop the rest; a console
 * that can't be reached does (the other links would only wait for the same timeout). [onProgress] gets the number of
 * the link that is about to be sent and the total; [onAccepted] is told about each link as soon as it is accepted;
 * [cancelled] is asked before each link.
 */
suspend fun sendInstallBatch(
    links: List<String>,
    send: suspend (String) -> InstallReply,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    onAccepted: (String) -> Unit = {},
    cancelled: () -> Boolean = { false }
): InstallBatch {
    val accepted = ArrayList<String>()
    val failed = ArrayList<Pair<String, String>>()
    var okText = ""
    var next = 0
    while (next < links.size) {
        if (cancelled()) break
        val link = links[next]
        onProgress(next + 1, links.size)
        val reply = send(link)
        next++
        if (reply.ok) {
            accepted.add(link)
            okText = reply.text
            onAccepted(link)
        } else {
            failed.add(link to reply.text)
            if (isConsoleGone(reply.text)) break
        }
    }
    return InstallBatch(accepted, failed, links.drop(next), okText)
}

/**
 * The message for the status line after sending [total] links. For a single link it is the installer's own answer; for
 * several it says how many were accepted, then what went wrong with the first that wasn't, then how many were not sent.
 */
fun batchMessage(b: InstallBatch, total: Int): String {
    if (total == 1) {
        val failure = b.failed.firstOrNull()
        if (failure != null) return failure.second
        if (b.lastOkText.isNotEmpty()) return b.lastOkText
    }
    val sb = StringBuilder(AppText.get(R.string.install_batch_done, b.accepted.size, total))
    b.failed.firstOrNull()?.let { sb.append('\n').append(it.second) }
    if (b.notSent.isNotEmpty()) sb.append('\n').append(AppText.get(R.string.install_batch_notsent, b.notSent.size))
    return sb.toString()
}
