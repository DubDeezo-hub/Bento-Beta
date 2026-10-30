package com.example.pssender

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

// ---------------------------------------------------------------------------------------------
// The pages of the Web tab as web views. The browser (Browser.kt) makes every decision; this file holds the parts that need
// the phone: a web view inside a pull-to-refresh layout for each tab, and the frame that shows the shown one.
// ---------------------------------------------------------------------------------------------

/** How long the pull-to-refresh spinner may turn when a page never says that it has finished. */
private const val SPINNER_MAX_MS = 15000L

/** The saved setting that tells whether sites are asked for their computer pages. */
private const val DESKTOP_KEY = "web_desktop"

/** The page of one tab: a web view, inside the layout that loads the page again when it is pulled down. */
private class WebTabPage(val web: WebView, val swipe: SwipeRefreshLayout, private val phone: PageWidth) : TabPage {
    override val url: String? get() = web.url
    override val title: String? get() = web.title
    override val canGoBack: Boolean get() = web.canGoBack()
    override val userAgent: String get() = web.settings.userAgentString

    override fun load(url: String) = web.loadUrl(url)

    override fun reload() = web.reload()

    override fun goBack() = web.goBack()

    override fun setShown(shown: Boolean) {
        if (shown) web.onResume() else web.onPause()
    }

    override fun useDesktop(on: Boolean) = applyBrowserMode(web, on, phone)

    override fun search(query: String) = web.findAllAsync(query)

    override fun searchStep(forward: Boolean) = web.findNext(forward)

    override fun clearSearch() = web.clearMatches()

    override fun release() {
        try {
            web.stopLoading()
            swipe.removeView(web)
            web.destroy()
        } catch (_: Exception) {
        }
    }
}

/** The frame the shown page sits in. A page that is already in it is left alone. */
private class WebStage(ctx: Context) : Stage {
    val container = FrameLayout(ctx)

    override fun show(page: TabPage) {
        val swipe = (page as? WebTabPage)?.swipe ?: return
        if (container.childCount == 1 && container.getChildAt(0) === swipe) return
        container.removeAllViews()
        container.addView(swipe, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun clear() {
        container.removeAllViews()
    }
}

/** The browser as the app uses it: the tabs ([model]) and the view that shows the shown page ([container]). */
class WebBrowser(val model: BrowserModel, val container: View)

/**
 * Makes the page of a tab: a web view for [url], set to the phone or the computer mode, inside a layout that loads it again
 * when it is pulled down. [events] hears from the page; the other three are for what the person has to decide.
 */
private fun makeWebPage(
    ctx: Context,
    url: String,
    desktop: Boolean,
    events: PageEvents,
    onOffSite: (String) -> Unit,
    onDownload: (PendingDownload) -> Unit,
    onLinkPressed: (String) -> Unit
): TabPage {
    val swipe = SwipeRefreshLayout(ctx)
    val hooks = object : WebHooks {
        override fun pageChanged(web: WebView) = events.changed()

        override fun pageFinished(web: WebView) {
            swipe.isRefreshing = false
            events.finished()
        }

        override fun offSite(url: String) = onOffSite(url)

        override fun download(d: PendingDownload) = onDownload(d)

        override fun linkPressed(url: String) = onLinkPressed(url)

        override fun findResult(web: WebView, active: Int, total: Int) = events.found(active, total)
    }
    val web = buildWebView(ctx, hooks)
    val phone = PageWidth.of(web)
    applyBrowserMode(web, desktop, phone)
    swipe.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    // Pulling down loads the page again; the spinner stops when the page is done, or after a while when it never says so
    swipe.setOnRefreshListener {
        web.reload()
        swipe.postDelayed({ swipe.isRefreshing = false }, SPINNER_MAX_MS)
    }
    // Pulling down only starts a reload when the page is at its top, so scrolling up a page never does
    swipe.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
    web.loadUrl(url)
    return WebTabPage(web, swipe, phone)
}

/**
 * Makes the browser of the Web tab. [savedTabs] is what [onTabsChanged] gave last; the tabs come back from it. [onOffSite],
 * [onDownload] and [onLinkPressed] hand on what a page wants the person to decide.
 */
fun makeWebBrowser(
    ctx: Context,
    homeUrl: () -> String,
    savedTabs: String,
    onOffSite: (String) -> Unit,
    onDownload: (PendingDownload) -> Unit,
    onLinkPressed: (String) -> Unit,
    onTabsChanged: (String) -> Unit
): WebBrowser {
    val stage = WebStage(ctx)
    val model = BrowserModel(
        homeUrl = homeUrl,
        savedTabs = savedTabs,
        startDesktop = readPref(ctx, DESKTOP_KEY, "0") == "1",
        stage = stage,
        makePage = { _, url, desktop, events -> makeWebPage(ctx, url, desktop, events, onOffSite, onDownload, onLinkPressed) },
        onFull = { Toast.makeText(ctx, AppText.get(R.string.web_tabs_full, TABS_MAX), Toast.LENGTH_SHORT).show() },
        onDesktop = { on ->
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(DESKTOP_KEY, if (on) "1" else "0").apply()
        },
        onTabsChanged = onTabsChanged
    )
    return WebBrowser(model, stage.container)
}
