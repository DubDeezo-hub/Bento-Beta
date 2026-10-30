package com.example.pssender

import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// The Web tab's decisions that need no phone: which tabs are open and which one is shown, what a tab is called, which
// address a long press on a link means, what the browser tells a site about itself, and what the find counter says.
// The screen and the web views that use them are in Browser.kt.
// ---------------------------------------------------------------------------------------------

/** The most tabs open at once. Every opened page keeps memory, and a phone has little of it to spare. */
const val TABS_MAX = 8

/** One browser tab: its address and title as the page last reported them, and whether the page has a page before it. */
data class BrowserTab(val id: Int, val url: String, val title: String = "", val canGoBack: Boolean = false)

/**
 * The open tabs in the order they are listed, the number of the one shown, and the number the next new tab gets.
 * There is always at least one tab, and [currentId] is one of them.
 */
data class TabsState(val tabs: List<BrowserTab>, val currentId: Int, val nextId: Int) {
    /** The tab being shown. */
    val current: BrowserTab get() = tabs.firstOrNull { it.id == currentId } ?: tabs.first()
}

/** The tabs of a browser that has just been opened: one tab, for [url]. */
fun firstTabs(url: String): TabsState = TabsState(listOf(BrowserTab(1, url)), 1, 2)

/**
 * [s] with a new tab for [url] right after the shown one; the new tab is the one shown. Null when [TABS_MAX] tabs are
 * open already.
 */
fun withNewTab(s: TabsState, url: String): TabsState? {
    if (s.tabs.size >= TABS_MAX) return null
    val after = s.tabs.indexOfFirst { it.id == s.currentId } + 1
    val tab = BrowserTab(s.nextId, url)
    return TabsState(s.tabs.take(after) + tab + s.tabs.drop(after), tab.id, s.nextId + 1)
}

/** [s] with tab [id] shown; unchanged when there is no such tab. */
fun withTabShown(s: TabsState, id: Int): TabsState = if (s.tabs.any { it.id == id }) s.copy(currentId = id) else s

/**
 * [s] without tab [id]. When the shown tab is closed, the tab that takes its place is shown: the next one, or the one before
 * it when it was the last. The last tab is never left out: closing it gives a new tab for [blankUrl].
 */
fun withoutTab(s: TabsState, id: Int, blankUrl: String): TabsState {
    val at = s.tabs.indexOfFirst { it.id == id }
    if (at < 0) return s
    if (s.tabs.size == 1) return TabsState(listOf(BrowserTab(s.nextId, blankUrl)), s.nextId, s.nextId + 1)
    val rest = s.tabs.filterIndexed { i, _ -> i != at }
    val shown = if (id == s.currentId) rest[minOf(at, rest.size - 1)].id else s.currentId
    return TabsState(rest, shown, s.nextId)
}

/** [s] with what the page of tab [id] reported. What is null stays as it was; a tab that is gone is ignored. */
fun withPage(s: TabsState, id: Int, url: String? = null, title: String? = null, canGoBack: Boolean? = null): TabsState =
    s.copy(tabs = s.tabs.map { t ->
        if (t.id != id) t else t.copy(url = url ?: t.url, title = title ?: t.title, canGoBack = canGoBack ?: t.canGoBack)
    })

// ----- names -----

/** The host of a web address in lower case ("www.example.com"); empty when it has none. */
fun webHost(url: String): String {
    val rest = url.trim().substringAfter("://", "")
    if (rest.isEmpty()) return ""
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return host.lowercase()
}

/** The first [n] characters of [text], without cutting a letter that takes two characters (an emoji) in half. */
private fun headOf(text: String, n: Int): String {
    val t = text.take(n)
    return if (t.isNotEmpty() && Character.isHighSurrogate(t.last())) t.dropLast(1) else t
}

/** The last [n] characters of [text], without cutting a letter that takes two characters (an emoji) in half. */
private fun tailOf(text: String, n: Int): String {
    val t = text.takeLast(n)
    return if (t.isNotEmpty() && Character.isLowSurrogate(t.first())) t.drop(1) else t
}

/** [text] cut to at most [max] characters (at least 1), with … where the end was cut off. */
fun cutEnd(text: String, max: Int): String {
    val m = max.coerceAtLeast(1)
    return if (text.length <= m) text else headOf(text, m - 1) + "…"
}

/** [text] cut to at most [max] characters (at least 1) by taking out the middle, so that both the beginning and the end stay readable. */
fun cutMiddle(text: String, max: Int): String {
    val m = max.coerceAtLeast(1)
    if (text.length <= m) return text
    val tail = (m - 1) / 2
    val head = m - 1 - tail
    return headOf(text, head) + "…" + tailOf(text, tail)
}

/** What a tab is called in the list: the title of its page, else its site, else its address; [blank] when it has nothing yet. */
fun tabLabel(t: BrowserTab, blank: String, max: Int = 40): String {
    val title = t.title.trim()
    if (title.isNotEmpty()) return cutEnd(title, max)
    val host = webHost(t.url)
    if (host.isNotEmpty()) return cutEnd(host, max)
    val url = t.url.trim()
    return if (url.isEmpty() || url == "about:blank") blank else cutEnd(url, max)
}

private val SCHEME_RE = Regex("^https?://", RegexOption.IGNORE_CASE)

/** The address as the menu of a link shows it: without "https://", and cut in the middle when it is long. */
fun linkTitle(url: String, max: Int = 60): String = cutMiddle(url.trim().replaceFirst(SCHEME_RE, ""), max)

// ----- a long press on a link -----

/** What was pressed: a link made of text, or a picture that is a link. */
enum class LinkHit { TEXT_LINK, PICTURE_LINK }

/**
 * The web address a long press on a link means, or null when there is none (an e-mail link, a "javascript:" link, nothing).
 * For a text link the page gives the address at once ([extra]). For a picture that is a link, [extra] is the address of the
 * picture, which is not what the person pressed; the address of the link itself ([href]) has to be asked from the page.
 */
fun linkAddress(hit: LinkHit, extra: String?, href: String?): String? {
    val options = if (hit == LinkHit.TEXT_LINK) listOf(extra, href) else listOf(href)
    return options.firstNotNullOfOrNull { o -> o?.trim()?.takeIf { isWebLink(it) } }
}

// ----- what the browser says about itself -----

/** What the browser tells a site when it pretends to be Chrome on a computer and cannot work out a better text from its own. */
const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** The web view's own text about itself, [ua], without the "wv" mark that makes some sites treat the app like a bot. */
fun phoneUserAgent(ua: String): String = ua.replace("; wv", "")

private val ANDROID_SYSTEM_RE = Regex("""\(Linux; Android[^)]*\)""")
private val VERSION_TOKEN_RE = Regex("""Version/[\d.]+ """)

/** [ua] changed to say "Chrome on a computer" instead of "Chrome on an Android phone", so sites send their full pages. */
fun desktopUserAgent(ua: String): String {
    val plain = phoneUserAgent(ua)
    if (!ANDROID_SYSTEM_RE.containsMatchIn(plain)) return DESKTOP_UA
    return plain.replace(ANDROID_SYSTEM_RE, "(X11; Linux x86_64)").replace(" Mobile Safari/", " Safari/").replace(VERSION_TOKEN_RE, "")
}

/** What the browser tells sites: the phone's text, or the computer's when [desktop] is on. [ua] is the web view's own text. */
fun userAgentFor(desktop: Boolean, ua: String): String = if (desktop) desktopUserAgent(ua) else phoneUserAgent(ua)

// ----- find in page -----

/** "3/12" for the third of twelve matches ([active] counts from 0, as the web view does); null when there is none. */
fun findCounter(active: Int, total: Int): String? = if (total <= 0) null else "${active.coerceIn(0, total - 1) + 1}/$total"

// ----- keeping the tabs when the screen is rebuilt -----

/** The longest address that is kept with a tab; a longer one is kept as the front page of its site. */
const val TAB_URL_KEEP_MAX = 2000

/** The longest title that is kept with a tab. */
const val TAB_TITLE_KEEP_MAX = 80

/** The front page of the site of [url] ("https://host/"), or an empty text when [url] has no host. */
fun frontPage(url: String): String {
    val host = webHost(url)
    if (host.isEmpty()) return ""
    return (if (url.trim().startsWith("http://", ignoreCase = true)) "http" else "https") + "://" + host + "/"
}

/** [s] written as text, to be kept while the screen is rebuilt. The back button history is not part of it. */
fun tabsToJson(s: TabsState): String {
    val arr = JSONArray()
    for (t in s.tabs) {
        val url = if (t.url.length > TAB_URL_KEEP_MAX) frontPage(t.url) else t.url
        arr.put(JSONObject().put("i", t.id).put("u", url).put("t", headOf(t.title, TAB_TITLE_KEEP_MAX)))
    }
    return JSONObject().put("c", s.currentId).put("n", s.nextId).put("t", arr).toString()
}

/**
 * The tabs written by [tabsToJson]. Anything that is not usable (no text, damaged text, an address that is not a web
 * address, too many tabs) is made right or left out; when nothing is left there is one tab for [blankUrl].
 */
fun tabsFromJson(text: String?, blankUrl: String): TabsState {
    if (text.isNullOrBlank()) return firstTabs(blankUrl)
    return try {
        val o = JSONObject(text)
        val arr = o.getJSONArray("t")
        val tabs = ArrayList<BrowserTab>()
        val seen = HashSet<Int>()
        for (i in 0 until arr.length()) {
            if (tabs.size >= TABS_MAX) break
            val t = arr.optJSONObject(i) ?: continue
            val id = t.optInt("i", -1)
            if (id < 1 || !seen.add(id)) continue
            val url = t.optString("u", "").trim().let { if (isWebLink(it)) it else blankUrl }
            val title = if (t.isNull("t")) "" else t.optString("t", "")
            tabs.add(BrowserTab(id, url, headOf(title, TAB_TITLE_KEEP_MAX)))
        }
        if (tabs.isEmpty()) return firstTabs(blankUrl)
        val current = o.optInt("c", -1).let { c -> if (tabs.any { it.id == c }) c else tabs.first().id }
        TabsState(tabs, current, maxOf(o.optInt("n", 0), tabs.maxOf { it.id } + 1))
    } catch (_: Exception) {
        firstTabs(blankUrl)
    }
}
