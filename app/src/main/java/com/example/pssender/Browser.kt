package com.example.pssender

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// The tabs of the Web tab: which pages are open, which one is shown, what Back does, the search in a page, and the windows
// that belong to them (the list of tabs, the menu of a pressed link, the find bar). Nothing here needs a phone: the pages
// are reached through TabPage, which the app fills in with web views (BrowserWeb.kt). The rules about tabs, names and
// addresses are in BrowserLogic.kt.
// ---------------------------------------------------------------------------------------------

/**
 * What the browser needs from the page of one tab. In the app it is a web view (see BrowserWeb.kt); the checks of the
 * browser use a stand-in, so everything the browser decides can be tried without a phone.
 */
interface TabPage {
    /** The address of the page, or null when it has none yet. */
    val url: String?

    /** The title of the page, or null when it has none yet. */
    val title: String?

    /** True when there is a page before this one. */
    val canGoBack: Boolean

    /** What the page tells sites about itself (a download started from a link says the same). */
    val userAgent: String

    fun load(url: String)

    fun reload()

    fun goBack()

    /** The shown page runs; the others stand still. */
    fun setShown(shown: Boolean)

    /** Asks sites for their computer pages (on) or their phone pages (off). */
    fun useDesktop(on: Boolean)

    /** Looks for [query] in the page and marks the matches; [PageEvents.found] tells how many there are. */
    fun search(query: String)

    /** Goes to the next match, or the one before it. */
    fun searchStep(forward: Boolean)

    /** Takes the marks of a search off the page. */
    fun clearSearch()

    /** Lets go of the page for good. */
    fun release()
}

/** What the page of one tab tells the browser. */
interface PageEvents {
    /** The address, the title or the way back of the page may have changed. */
    fun changed()

    /** The page has finished loading (or has failed to). */
    fun finished()

    /** A search in the page found [total] matches; [active] (counting from 0) is the shown one. */
    fun found(active: Int, total: Int)
}

/** The place where the shown page is put on the screen. */
interface Stage {
    /** Puts [page] on the screen in place of the one that was there. */
    fun show(page: TabPage)

    /** Takes whatever is shown off the screen. */
    fun clear()
}

/**
 * The browser of the Web tab. It holds the open tabs ([state]), makes the page of a tab when the tab is shown for the first
 * time ([makePage]) and has the [stage] show the page of the shown tab. [savedTabs] is what [onTabsChanged] gave last (the
 * tabs written as text); the tabs come back from it when the screen is rebuilt. Everything here runs on the main thread.
 */
class BrowserModel(
    private val homeUrl: () -> String,
    savedTabs: String,
    startDesktop: Boolean,
    private val stage: Stage,
    private val makePage: (id: Int, url: String, desktop: Boolean, events: PageEvents) -> TabPage,
    private val onFull: () -> Unit,
    private val onDesktop: (Boolean) -> Unit,
    private val onTabsChanged: (String) -> Unit
) {
    /** The open tabs and the shown one. */
    var state by mutableStateOf(tabsFromJson(savedTabs, homeUrl()))
        private set

    /** True while the sites are asked for their computer pages. */
    var desktop by mutableStateOf(startDesktop)
        private set

    /** The find bar: whether it is open, what was typed, which match is the shown one (from 0) and how many there are. */
    var findOpen by mutableStateOf(false)
        private set
    var findQuery by mutableStateOf("")
        private set
    var findActive by mutableStateOf(0)
        private set
    var findTotal by mutableStateOf(0)
        private set

    /** True when the Back button has something to do in the browser: close the find bar, go back a page, or close a tab. */
    var backNeeded by mutableStateOf(state.tabs.size > 1)
        private set

    /** The pages of the tabs that have been shown (a tab that has not been shown since the screen was built has none yet). */
    private val pages = HashMap<Int, TabPage>()

    /** The tabs whose page still has to be loaded again because the phone/computer setting changed while they were not shown. */
    private val stale = HashSet<Int>()

    private var kept = ""

    init {
        show(state.currentId)
    }

    // ----- what the screen asks -----

    /** The address of the page that is shown, or null when there is none yet. */
    val currentUrl: String? get() = pages[state.currentId]?.url

    /** The title of the page that is shown. */
    val currentTitle: String? get() = pages[state.currentId]?.title

    /** What the browser tells sites about itself right now. */
    fun userAgent(): String = pages[state.currentId]?.userAgent ?: FALLBACK_UA

    /** Loads [url] in the shown tab. */
    fun load(url: String) {
        pages[state.currentId]?.load(url)
    }

    /** Loads the shown page again. */
    fun reload() {
        pages[state.currentId]?.reload()
    }

    /** Opens a new tab for [url] and shows it. False (after telling why) when too many tabs are open. */
    fun openNew(url: String): Boolean {
        val next = withNewTab(state, url)
        if (next == null) {
            onFull()
            return false
        }
        endFind()
        state = next
        show(next.currentId)
        changed()
        return true
    }

    /** Opens a new tab with the homepage. */
    fun newTab(): Boolean = openNew(homeUrl())

    /** Shows tab [id]. */
    fun select(id: Int) {
        val next = withTabShown(state, id)
        if (next == state) return
        endFind()
        state = next
        show(id)
        changed()
    }

    /** Closes tab [id]. Closing the last one leaves a new tab with the homepage. */
    fun close(id: Int) {
        val next = withoutTab(state, id, homeUrl())
        if (next == state) return
        endFind()
        state = next
        val gone = pages.remove(id)
        stale.remove(id)
        show(next.currentId)
        gone?.release()
        changed()
    }

    /** What the Back button does: closes the find bar, else goes back a page, else closes the tab when others are open. */
    fun back() {
        val page = pages[state.currentId]
        when {
            findOpen -> endFind()
            page != null && page.canGoBack -> page.goBack()
            state.tabs.size > 1 -> close(state.currentId)
        }
    }

    /** Turns the computer pages of sites on or off. The shown page is loaded again; the other tabs follow when they are shown. */
    fun switchDesktop(on: Boolean) {
        if (on == desktop) return
        desktop = on
        onDesktop(on)
        for ((id, page) in pages) {
            page.useDesktop(on)
            if (id == state.currentId) page.reload() else stale.add(id)
        }
    }

    // ----- find in page -----

    /** Opens the find bar. */
    fun startFind() {
        if (findOpen) return
        findOpen = true
        findQuery = ""
        findActive = 0
        findTotal = 0
        refreshBack()
    }

    /** Searches the shown page for [query]; an empty one clears the marks. */
    fun find(query: String) {
        findQuery = query
        findActive = 0
        findTotal = 0
        val page = pages[state.currentId] ?: return
        if (query.isBlank()) page.clearSearch() else page.search(query)
    }

    /** Goes to the next match, or the one before it. */
    fun findStep(forward: Boolean) {
        if (findQuery.isBlank() || findTotal <= 0) return
        pages[state.currentId]?.searchStep(forward)
    }

    /** Closes the find bar and takes the marks off the page. */
    fun endFind() {
        if (!findOpen) return
        pages[state.currentId]?.clearSearch()
        findOpen = false
        findQuery = ""
        findActive = 0
        findTotal = 0
        refreshBack()
    }

    // ----- the end -----

    /** Lets go of every page. Called when the screen is gone for good. */
    fun destroy() {
        stage.clear()
        for (page in pages.values) page.release()
        pages.clear()
        stale.clear()
    }

    // ----- inside -----

    /** Has the stage show the page of tab [id] (making the page first when the tab has not been shown yet). */
    private fun show(id: Int) {
        val tab = state.tabs.firstOrNull { it.id == id } ?: return
        val page = pages.getOrPut(id) { makePage(id, tab.url, desktop, eventsFor(id)) }
        stage.show(page)
        // The pages that are not shown stand still
        for ((other, p) in pages) p.setShown(other == id)
        if (stale.remove(id)) page.reload()
    }

    private fun eventsFor(id: Int): PageEvents = object : PageEvents {
        override fun changed() = onPage(id)
        override fun finished() = onFinished(id)
        override fun found(active: Int, total: Int) = onFound(id, active, total)
    }

    /** The page of tab [id] reported its address, title or way back. A tab that was closed meanwhile is ignored. */
    private fun onPage(id: Int) {
        val page = pages[id] ?: return
        val url = page.url?.takeIf { isWebLink(it) }
        val next = withPage(state, id, url = url, title = page.title, canGoBack = page.canGoBack)
        if (next != state) {
            state = next
            changed()
        }
    }

    private fun onFinished(id: Int) {
        val page = pages[id] ?: return
        onPage(id)
        // The page that was searched is gone: search the new one for the same words
        if (id == state.currentId && findOpen && findQuery.isNotBlank()) page.search(findQuery)
    }

    private fun onFound(id: Int, active: Int, total: Int) {
        if (pages[id] == null || id != state.currentId || !findOpen || findQuery.isBlank()) return
        findActive = active
        findTotal = total
    }

    private fun refreshBack() {
        backNeeded = findOpen || state.current.canGoBack || state.tabs.size > 1
    }

    /** The tabs changed: the Back button is looked at again, and the tabs are handed on to be kept while the screen is rebuilt. */
    private fun changed() {
        refreshBack()
        val text = tabsToJson(state)
        if (text != kept) {
            kept = text
            onTabsChanged(text)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Windows
// ---------------------------------------------------------------------------------------------

/** The list of open tabs: tap one to show it, ✕ to close it, and a button for a new one. */
@Composable
fun TabsDialog(browser: BrowserModel, onClose: () -> Unit) {
    val s = browser.state
    val blank = str(R.string.web_tab_new)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.web_tabs_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                s.tabs.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    browser.select(t.id)
                                    onClose()
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                (if (t.id == s.currentId) "● " else "") + tabLabel(t, blank),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                linkTitle(t.url),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { browser.close(t.id) }) { Text("✕") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (browser.newTab()) onClose() }) { Text(str(R.string.web_tab_new)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

/** What a long press on a link offers: open it here, open it in a new tab, copy it, or send or download it. */
@Composable
fun LinkMenuDialog(
    url: String,
    onOpen: () -> Unit,
    onNewTab: () -> Unit,
    onCopy: () -> Unit,
    onUse: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(linkTitle(url), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onClose()
                        onOpen()
                    }
                ) { Text(str(R.string.action_open)) }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onClose()
                        onNewTab()
                    }
                ) { Text(str(R.string.web_link_new_tab)) }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onClose()
                        onCopy()
                    }
                ) { Text(str(R.string.web_link_copy)) }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onClose()
                        onUse()
                    }
                ) { Text(str(R.string.web_link_use)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

/** The find bar: a box for the words, the number of the match, and buttons to go to the next or the earlier one. */
@Composable
fun FindBar(browser: BrowserModel) {
    val focus = remember { FocusRequester() }
    // The keyboard comes up at once; if the box is not ready yet the person taps it
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (_: Exception) {
        }
    }
    val counter = findCounter(browser.findActive, browser.findTotal)
        ?: if (browser.findQuery.isNotBlank()) str(R.string.web_find_none) else ""
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = browser.findQuery,
                onValueChange = { browser.find(it) },
                label = { Text(str(R.string.web_find_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
            TextButton(onClick = { browser.endFind() }) { Text("✕") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(counter, modifier = Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { browser.findStep(false) }) { Text("▲") }
            TextButton(onClick = { browser.findStep(true) }) { Text("▼") }
        }
    }
}
