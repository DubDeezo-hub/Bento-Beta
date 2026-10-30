package com.example.pssender

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Starting homepage for the Web tab. It can be changed inside the app. */
const val DEFAULT_HOME = "https://www.superpsx.com/category/ps5/ps5-games/"

/** [input] as a web address: "example.com" becomes "https://example.com", and an address that has http:// or https:// (in any case) is kept. */
fun normalizeUrl(input: String): String {
    val t = input.trim()
    return if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) t else "https://$t"
}

/** Sites the user has agreed to open during this session (plus the homepage's own site). */
val allowedSites = mutableSetOf<String>()

/** Rough "site" of a host, e.g. www.superpsx.com -> superpsx.com. Good enough to spot jumps to another site. */
fun siteOf(host: String?): String {
    val parts = (host ?: "").lowercase().split('.')
    if (parts.size <= 2) return parts.joinToString(".")
    // An address such as 192.168.1.5 is its own site (not its last two numbers)
    if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } }) return parts.joinToString(".")
    val secondLevel = setOf("co", "com", "org", "net", "gov", "ac")
    val keep = if (parts.last().length == 2 && parts[parts.size - 2] in secondLevel) 3 else 2
    return parts.takeLast(keep).joinToString(".")
}

/** True when [target] is on a different site than the page currently shown and the user hasn't allowed it. */
fun isOffSite(view: WebView?, target: Uri): Boolean {
    val from = siteOf(view?.url?.let { Uri.parse(it).host })
    val to = siteOf(target.host)
    return from.isNotEmpty() && to.isNotEmpty() && from != to && to !in allowedSites
}

/** A download (or shared link) waiting for the user to choose where it should go. */
data class PendingDownload(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?
)

/** Saves a download to the phone's Downloads folder with the system download manager. */
fun saveToPhone(ctx: Context, d: PendingDownload) {
    val req = DownloadManager.Request(Uri.parse(d.url))
        .setMimeType(d.mimeType)
        .addRequestHeader("User-Agent", d.userAgent)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            URLUtil.guessFileName(d.url, d.contentDisposition, d.mimeType)
        )
    CookieManager.getInstance().getCookie(d.url)?.let { req.addRequestHeader("Cookie", it) }
    (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    Toast.makeText(ctx, AppText.get(R.string.web_download_started), Toast.LENGTH_SHORT).show()
}

/** What to tell the user when the site answered the page with an error code such as 404 or 503. */
fun httpErrorText(code: Int): String =
    AppText.get(if (code in 500..599) R.string.web_http_server else R.string.web_http_other, code.toString())

// ---------------------------------------------------------------------------------------------
// Ad block: requests to known ad-network domains are answered with an empty page
// ---------------------------------------------------------------------------------------------

/** True when [host] is one of [domains] or a subdomain of one (ads.example.com matches example.com). */
fun hostMatches(host: String, domains: Set<String>): Boolean {
    var h = host
    while (true) {
        if (h in domains) return true
        val dot = h.indexOf('.')
        if (dot < 0) return false
        h = h.substring(dot + 1)
    }
}

/**
 * Turns the text typed into the block-list editor into domain names: one per line, "#" starts a comment (also after a
 * domain), a pasted address like https://ads.example.com/page becomes ads.example.com, and a line of a hosts file
 * ("0.0.0.0 ads.example.com") gives the domain after the address. Lines that aren't a domain are ignored.
 */
fun parseHosts(text: String): Set<String> =
    text.lineSequence()
        .map { it.substringBefore('#').trim().lowercase() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val words = line.split(Regex("\\s+"))
            if (words.size == 2 && parseIpv4(words[0]) != null) words[1] else line
        }
        .map {
            it.removePrefix("http://").removePrefix("https://")
                .substringBefore('/').substringBefore(':')
                .removePrefix("*.").trim('.')
        }
        .filter { h -> h.contains('.') && h.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' } }
        .toSet()

object AdBlock {
    /** Well-known advertising and pop-up networks. Anything loaded from these (or their subdomains) is dropped. */
    val builtIn: Set<String> = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "adnxs.com", "adsrvr.org", "advertising.com", "adform.net", "adition.com",
        "amazon-adsystem.com", "openx.net", "pubmatic.com", "rubiconproject.com", "casalemedia.com",
        "contextweb.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "revcontent.com",
        "mgid.com", "adskeeper.com", "zedo.com", "yieldmo.com", "smartadserver.com", "sharethrough.com",
        "indexww.com", "33across.com", "bidswitch.net", "sovrn.com", "lijit.com", "media.net", "moatads.com",
        "serving-sys.com", "2mdn.net", "adsafeprotected.com", "doubleverify.com", "teads.tv", "inmobi.com",
        "smaato.net", "mopub.com", "applovin.com", "a-ads.com", "bidvertiser.com",
        // pop-up / pop-under networks, the usual source of tap hijacks and surprise redirects
        "popads.net", "popcash.net", "propellerads.com", "adcash.com", "exoclick.com", "exosrv.com",
        "juicyads.com", "clickadu.com", "hilltopads.net", "adsterra.com", "trafficjunky.net", "onclickads.net"
    )

    val enabled = MutableStateFlow(true)
    val blockedCount = MutableStateFlow(0)

    @Volatile
    var custom: Set<String> = emptySet()

    /** True when a request to [host] should be dropped. Called from WebView's background threads. */
    fun shouldBlock(host: String?): Boolean {
        if (!enabled.value || host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return hostMatches(h, builtIn) || hostMatches(h, custom)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        enabled.value = prefs(ctx).getString("adblock_on", "1") != "0"
        custom = parseHosts(customText(ctx))
    }

    /** The user's own list, exactly as typed. */
    fun customText(ctx: Context): String = prefs(ctx).getString("adblock_custom", "") ?: ""

    fun save(ctx: Context, on: Boolean, text: String) {
        prefs(ctx).edit().putString("adblock_on", if (on) "1" else "0").putString("adblock_custom", text).apply()
        enabled.value = on
        custom = parseHosts(text)
    }
}

// ---------------------------------------------------------------------------------------------
// Bookmarks
// ---------------------------------------------------------------------------------------------

data class Bookmark(val title: String, val url: String)

const val BOOKMARKS_MAX = 100

fun bookmarksToJson(list: List<Bookmark>): String {
    val arr = JSONArray()
    for (b in list) arr.put(JSONObject().put("t", b.title).put("u", b.url))
    return arr.toString()
}

fun parseBookmarks(json: String?): List<Bookmark> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("u", "")
            if (url.isEmpty()) null else Bookmark(o.optString("t", "").ifBlank { url }, url)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Adds [b] to the end unless that address is already saved (or the list is full). Returns the list and whether it grew. */
fun withBookmark(list: List<Bookmark>, b: Bookmark): Pair<List<Bookmark>, Boolean> =
    if (list.any { it.url == b.url } || list.size >= BOOKMARKS_MAX) list to false else (list + b) to true

object BookmarkStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Bookmark> = parseBookmarks(prefs(ctx).getString("bookmarks_v1", null))

    fun save(ctx: Context, list: List<Bookmark>) {
        prefs(ctx).edit().putString("bookmarks_v1", bookmarksToJson(list)).apply()
    }
}

// ---------------------------------------------------------------------------------------------
// The browser itself
// ---------------------------------------------------------------------------------------------

/**
 * What a web page tells the app. Every web view of the browser has its own set, so the app knows which tab it was
 * (see Browser.kt, which listens).
 */
interface WebHooks {
    /** The address, the title or the way back of the page may have changed. */
    fun pageChanged(web: WebView)

    /** The page has finished loading (or has failed to). */
    fun pageFinished(web: WebView)

    /** The page wants to go to another site; the person has to agree first. */
    fun offSite(url: String)

    /** The page offers a file. */
    fun download(d: PendingDownload)

    /** A link was pressed for a long time. [url] is where it goes. */
    fun linkPressed(url: String)

    /** A search in the page found [total] matches, and [active] (counting from 0) is the shown one. */
    fun findResult(web: WebView, active: Int, total: Int)
}

/** How a web view lays out the width of a page when it is built (the phone's way), kept to go back to it from the computer mode. */
class PageWidth(val wide: Boolean, val overview: Boolean) {
    companion object {
        fun of(web: WebView): PageWidth = PageWidth(web.settings.useWideViewPort, web.settings.loadWithOverviewMode)
    }
}

/**
 * Sets the way [web] presents itself to sites: as the phone it is, or ([desktop]) as a computer, with a page laid out wide,
 * fitted to the screen and possible to pinch. [phone] is how the view was built; with [desktop] off the view works as it
 * always did.
 */
fun applyBrowserMode(web: WebView, desktop: Boolean, phone: PageWidth) {
    val s = web.settings
    s.userAgentString = userAgentFor(desktop, WebSettings.getDefaultUserAgent(web.context))
    s.useWideViewPort = desktop || phone.wide
    s.loadWithOverviewMode = desktop || phone.overview
    s.builtInZoomControls = desktop
    s.displayZoomControls = false
}

/**
 * Makes a web view for one tab. It is not given a page and not given a phone or computer mode: the browser does both
 * (see [applyBrowserMode]).
 */
@SuppressLint("SetJavaScriptEnabled")
fun buildWebView(ctx: Context, hooks: WebHooks): WebView {
    AdBlock.load(ctx)
    val web = WebView(ctx)
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        setSupportMultipleWindows(true)
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(web, true)
    }

    web.webViewClient = object : WebViewClient() {
        // http(s) links load inside the WebView; other schemes (market:, intent:, ...) go to the system
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            if (uri.scheme == "http" || uri.scheme == "https") {
                // Ads often hijack a tap and bounce to another site: ask before leaving the current one
                if (request?.isForMainFrame == true && isOffSite(view, uri)) {
                    hooks.offSite(uri.toString())
                    return true
                }
                return false
            }
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
            return true
        }

        // Ad block: parts of a page (scripts, images, frames) from ad networks get an empty answer.
        // The page being opened itself is never blocked, so typing an address always works.
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val r = request ?: return null
            if (!r.isForMainFrame && AdBlock.shouldBlock(r.url?.host)) {
                AdBlock.blockedCount.update { it + 1 }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            view?.let { hooks.pageChanged(it) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            view?.let { hooks.pageFinished(it) }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            view?.let { hooks.pageChanged(it) }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                Toast.makeText(ctx, AppText.get(R.string.web_load_failed, error?.description?.toString().orEmpty()), Toast.LENGTH_LONG).show()
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true) {
                Toast.makeText(ctx, httpErrorText(errorResponse?.statusCode ?: 0), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Links that open a "new window" (target=_blank) load in this same WebView; automatic popups are ignored.
    web.webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            view?.let { hooks.pageChanged(it) }
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (!isUserGesture) return false
            val main = view ?: return false
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(main.context)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.let { u ->
                        if (isOffSite(main, u)) hooks.offSite(u.toString()) else main.loadUrl(u.toString())
                    }
                    v?.destroy()
                    return true
                }
            }
            transport.webView = popup
            resultMsg?.sendToTarget()
            return true
        }
    }

    // WebView doesn't download files by itself: ask the app where the file should go.
    web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        hooks.download(PendingDownload(url, userAgent, contentDisposition, mimeType))
    }

    // Pressing a link for a while offers a menu (see LinkMenuDialog); pressing anything else works as it always did
    web.setOnLongClickListener { v ->
        val hit = web.hitTestResult
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                val url = linkAddress(LinkHit.TEXT_LINK, hit.extra, null)
                if (url == null) {
                    false
                } else {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    hooks.linkPressed(url)
                    true
                }
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // For a picture that is a link the page only gives the address of the picture; the address of the link is asked next
                val picture = hit.extra
                val answer = Handler(Looper.getMainLooper()) { msg ->
                    val url = linkAddress(LinkHit.PICTURE_LINK, picture, msg.data?.getString("url"))
                    if (url != null) hooks.linkPressed(url)
                    true
                }
                web.requestFocusNodeHref(answer.obtainMessage())
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                true
            }
            else -> false
        }
    }

    // The search in the page tells how many matches it found and which one is shown
    web.setFindListener { active, total, _ -> hooks.findResult(web, active, total) }

    return web
}

@Composable
fun BookmarksDialog(
    bookmarks: List<Bookmark>,
    onOpen: (Bookmark) -> Unit,
    onMakeHome: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.web_bookmarks)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (bookmarks.isEmpty()) {
                    Text(str(R.string.web_bookmarks_empty, str(R.string.web_add)))
                } else {
                    Text(
                        str(R.string.web_bookmarks_help, str(R.string.action_home)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                bookmarks.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    onOpen(b)
                                    onClose()
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(b.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onMakeHome(b) }) { Text(str(R.string.action_home)) }
                        TextButton(onClick = { onRemove(b) }) { Text("✕") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

@Composable
fun AdBlockDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val on by AdBlock.enabled.collectAsState()
    val blocked by AdBlock.blockedCount.collectAsState()
    var text by remember { mutableStateOf(AdBlock.customText(ctx)) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.adblock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(str(R.string.adblock_switch), modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { AdBlock.save(ctx, it, text) })
                }
                Text(
                    str(R.string.adblock_info, blocked, AdBlock.builtIn.size),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        AdBlock.save(ctx, on, it)
                    },
                    label = { Text(str(R.string.adblock_own)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_done)) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebTab(browser: BrowserModel, container: View, homeUrl: String, onHomeChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bookmarks by remember { mutableStateOf(BookmarkStore.load(ctx)) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showAds by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    val adsOn by AdBlock.enabled.collectAsState()
    val blocked by AdBlock.blockedCount.collectAsState()

    if (showBookmarks) {
        BookmarksDialog(
            bookmarks = bookmarks,
            onOpen = { b ->
                allowedSites.add(siteOf(Uri.parse(b.url).host))
                browser.load(b.url)
            },
            onMakeHome = { b ->
                onHomeChange(b.url)
                Toast.makeText(ctx, AppText.get(R.string.web_home_set, b.title), Toast.LENGTH_SHORT).show()
            },
            onRemove = { b ->
                bookmarks = bookmarks.filter { it.url != b.url }
                BookmarkStore.save(ctx, bookmarks)
            },
            onClose = { showBookmarks = false }
        )
    }
    if (showAds) AdBlockDialog { showAds = false }
    if (showTabs) TabsDialog(browser) { showTabs = false }

    var showMenu by remember { mutableStateOf(false) }
    var showHome by remember { mutableStateOf(false) }
    if (showHome) {
        HomePageDialog(homeUrl, onSave = { url ->
            onHomeChange(url)
            browser.load(url)
        }) { showHome = false }
    }

    /** Bookmarks the page on screen. */
    fun bookmarkThisPage() {
        val url: String? = browser.currentUrl
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(ctx, AppText.get(R.string.web_open_first), Toast.LENGTH_SHORT).show()
        } else {
            val title = (browser.currentTitle ?: "").ifBlank { Uri.parse(url).host ?: url }
            val (list, added) = withBookmark(bookmarks, Bookmark(title.take(80), url))
            bookmarks = list
            BookmarkStore.save(ctx, list)
            Toast.makeText(
                ctx,
                AppText.get(if (added) R.string.web_bookmark_saved else R.string.web_bookmark_exists),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // One short row of the buttons used all the time; the rest is under More, so the page gets the room
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { browser.load(homeUrl) }) { Text(str(R.string.action_home), maxLines = 1) }
            TextButton(onClick = { browser.reload() }) { Text(str(R.string.web_reload), maxLines = 1) }
            TextButton(onClick = { showTabs = true }) { Text(str(R.string.web_tabs, browser.state.tabs.size), maxLines = 1) }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { showMenu = true }) { Text(str(R.string.web_more), maxLines = 1) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text(str(R.string.web_add)) }, onClick = {
                        showMenu = false
                        bookmarkThisPage()
                    })
                    DropdownMenuItem(text = { Text(str(R.string.web_bookmarks)) }, onClick = {
                        showMenu = false
                        showBookmarks = true
                    })
                    DropdownMenuItem(text = { Text(str(R.string.web_find)) }, onClick = {
                        showMenu = false
                        if (browser.findOpen) browser.endFind() else browser.startFind()
                    })
                    DropdownMenuItem(text = { Text(str(R.string.web_homepage_label)) }, onClick = {
                        showMenu = false
                        showHome = true
                    })
                    DropdownMenuItem(text = { Text(str(if (adsOn) R.string.adblock_on else R.string.adblock_off, blocked)) }, onClick = {
                        showMenu = false
                        showAds = true
                    })
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(str(R.string.web_desktop))
                                Switch(checked = browser.desktop, onCheckedChange = {
                                    showMenu = false
                                    browser.switchDesktop(it)
                                })
                            }
                        },
                        onClick = {
                            showMenu = false
                            browser.switchDesktop(!browser.desktop)
                        }
                    )
                }
            }
            TipButton(Tip.WEB, modifier = Modifier.padding(end = 4.dp))
        }
        if (browser.findOpen) {
            FindBar(browser)
        }
        // The browser keeps its view for as long as the app runs; if the screen that held it before still does, it is taken from there first
        AndroidView(
            factory = {
                (container.parent as? ViewGroup)?.removeView(container)
                container
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

/** The home page: the page Home opens, and the first one when the app starts. */
@Composable
private fun HomePageDialog(current: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var edit by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { TipTitle(str(R.string.web_homepage_label), Tip.WEB_HOME, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = edit, onValueChange = { edit = it },
                label = { Text(str(R.string.web_homepage_label)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(normalizeUrl(edit))
                onClose()
            }) { Text(str(R.string.action_set)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}
