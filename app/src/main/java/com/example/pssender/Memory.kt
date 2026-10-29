package com.example.pssender // change to match your project's package name

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// The Memory tab: a cheat engine for games on the console, through ps5debug-NG (Ps5Debug.kt, Scanner.kt).
// Three parts: Scan (find a value, narrow it down, change it, watch it), Cheats (ready-made cheat files, and your
// own saved addresses to set, freeze, find again, share) and Browse (processes, memory regions and a hex view).
// The state lives in MemorySession, so it survives tab switches.
// ---------------------------------------------------------------------------------------------

/** Memory-tab settings kept for the whole app. */
object MemoryPrefs {
    private const val PORT = "debug_port"
    private const val SKIP_SLOW = "memory_skip_slow"
    private const val CHEAT_SOURCE = "memory_cheat_source"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The debugger port: 744 on every PS5; the fake console uses 7744. */
    fun port(ctx: Context): String = prefs(ctx).getString(PORT, DEBUG_PORT.toString()) ?: DEBUG_PORT.toString()
    fun setPort(ctx: Context, v: String) = prefs(ctx).edit().putString(PORT, v).apply()

    fun skipSlow(ctx: Context): Boolean = prefs(ctx).getBoolean(SKIP_SLOW, true)
    fun setSkipSlow(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(SKIP_SLOW, v).apply()

    fun cheatSource(ctx: Context): String = prefs(ctx).getString(CHEAT_SOURCE, CheatFiles.DEFAULT_SOURCE) ?: CheatFiles.DEFAULT_SOURCE
    fun setCheatSource(ctx: Context, v: String) = prefs(ctx).edit().putString(CHEAT_SOURCE, v).apply()
}

/** The game first, then the rest by process number. */
fun sortProcesses(list: List<DbgProcess>, gamePid: Int): List<DbgProcess> =
    list.sortedWith(compareBy<DbgProcess>({ if (it.pid == gamePid && gamePid != 0) 0 else 1 }, { it.pid }))

/** Cheats in their groups, the ungrouped ones first, groups in the order they were first used. */
fun groupCheats(list: List<Cheat>): List<Pair<String, List<Cheat>>> {
    val order = LinkedHashMap<String, MutableList<Cheat>>()
    order[""] = ArrayList()
    for (c in list) order.getOrPut(c.group.trim()) { ArrayList() }.add(c)
    return order.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value }
}

/** "7F 45 4C 46" for bytes, and back (null if it is not hex). */
fun bytesText(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

/** A match the user tapped, a cheat being added or edited, a line of the hex view, or a setting. */
private sealed class MemoryDialog {
    class Hit(val hit: MemoryHit, val type: ValueType) : MemoryDialog()
    class EditCheat(val cheat: Cheat) : MemoryDialog()
    class NewCheat(val address: Long?, val type: ValueType, val value: String) : MemoryDialog()
    class HexLine(val address: Long, val bytes: ByteArray) : MemoryDialog()
    class WatchMenu(val watch: Watch) : MemoryDialog()
    object Import : MemoryDialog()
    object Source : MemoryDialog()
}

@Composable
fun MemoryTab(host: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val m = MemorySession
    m.attach(ctx)
    LaunchedEffect(host) { m.setHost(host) }
    var dialog by remember { mutableStateOf<MemoryDialog?>(null) }

    // Back steps out of the hex view, then out of the process, before it leaves the tab
    BackHandler(enabled = m.view == MemoryView.BROWSE && (m.hexAt >= 0 || m.openProc != null)) {
        if (m.hexAt >= 0) m.closeHex() else m.closeProcess()
    }

    // While the matches fit on the list (and while anything is watched), keep their values live
    val results = m.results
    val kept = m.keptTotal
    val watching = m.watches.isNotEmpty()
    LaunchedEffect(results, kept, watching, m.view) {
        if (m.view == MemoryView.SCAN && (watching || (m.searching && m.total <= MemorySession.SHOWN))) {
            while (true) {
                delay(MemorySession.LIVE_MS)
                m.refreshQuietly()
            }
        }
    }

    val s = m.snapshot
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header(s) }
        if (s != null) {
            item {
                ChipRow {
                    Chip(str(R.string.memory_view_scan), m.view == MemoryView.SCAN) { m.view = MemoryView.SCAN }
                    Chip(str(R.string.memory_view_cheats, m.cheats.size), m.view == MemoryView.CHEATS) { m.view = MemoryView.CHEATS }
                    Chip(str(R.string.memory_view_browse), m.view == MemoryView.BROWSE) { m.view = MemoryView.BROWSE }
                }
            }
            when (m.view) {
                MemoryView.SCAN -> scanItems(s) { dialog = it }
                MemoryView.CHEATS -> cheatItems(s, ctx) { dialog = it }
                MemoryView.BROWSE -> browseItems(s) { dialog = it }
            }
        }
    }

    val close = { dialog = null }
    when (val d = dialog) {
        is MemoryDialog.Hit -> HitDialog(d, onClose = close, onOpen = { dialog = it })
        is MemoryDialog.EditCheat -> CheatDialog(d.cheat, null, close)
        is MemoryDialog.NewCheat -> CheatDialog(null, d, close)
        is MemoryDialog.HexLine -> HexLineDialog(d, close)
        is MemoryDialog.WatchMenu -> WatchDialog(d.watch, onClose = close, onOpen = { dialog = it })
        MemoryDialog.Import -> ImportDialog(ctx, close)
        MemoryDialog.Source -> SourceDialog(close)
        null -> {}
    }
}

// ---------------------------------------------------------------------------------------------
// Header: port, connect, what is on the console
// ---------------------------------------------------------------------------------------------

@Composable
private fun Header(s: MemorySnapshot?) {
    val m = MemorySession
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s == null) {
            TipField(str(R.string.memory_port), m.port, Tip.MEM_DEBUGGER, number = true) { m.changePort(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !m.busy, onClick = { m.connect() }) {
                Text(str(if (s == null) R.string.memory_connect else R.string.memory_refresh))
            }
            if (s != null) {
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        if (s.game.pid == 0) str(R.string.memory_no_game) else s.game.name.ifBlank { s.game.titleId.ifBlank { "eboot.bin" } },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (s.game.pid == 0) str(R.string.memory_connected, s.info.brand, s.info.firmware)
                        else str(R.string.memory_game_detail, s.game.titleId.ifBlank { "?" }, s.game.version.ifBlank { "?" }, s.game.pid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (m.status.isNotEmpty()) {
            Text(m.status, style = MaterialTheme.typography.bodySmall)
            HelpLink(m.status)
        }
        if (m.scanning) {
            val all = m.progressAll
            LinearProgressIndicator(
                progress = { if (all > 0) (m.progressDone.toFloat() / all).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { m.cancel() }) { Text(str(R.string.action_cancel)) }
        }
        if (s == null) {
            Text(str(R.string.memory_intro), style = MaterialTheme.typography.bodySmall)
            Foldable(str(R.string.memory_test_title)) {
                Text(str(R.string.memory_test_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Scan
// ---------------------------------------------------------------------------------------------

private fun LazyListScope.scanItems(s: MemorySnapshot, open: (MemoryDialog) -> Unit) {
    val m = MemorySession
    if (s.game.pid == 0) {
        item { Text(str(R.string.memory_scan_needs_game), style = MaterialTheme.typography.bodySmall) }
        return
    }
    val searching = m.searching
    val t = m.searchType
    if (m.watches.isNotEmpty()) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TipTitle(str(R.string.memory_watches), Tip.MEM_WATCH, style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    m.watches.forEach { w ->
                        Chip(hexAddr(w.address).takeLast(8) + " = " + (w.value?.let { w.type.format(it) } ?: "…"), selected = false) {
                            open(MemoryDialog.WatchMenu(w))
                        }
                    }
                }
            }
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The kind of value is fixed for a search; a new search can change it
            TipTitle(str(R.string.memory_value_type), Tip.MEM_VALUE_TYPE, style = MaterialTheme.typography.labelLarge)
            ChipRow {
                ValueType.entries.forEach { vt ->
                    Chip(vt.label, t == vt, enabled = !searching && !m.busy) { m.type = vt }
                }
            }
            TipTitle(str(R.string.memory_scan_type), Tip.MEM_SCAN_TYPE, style = MaterialTheme.typography.labelLarge)
            ChipRow {
                ScanCompare.entries.filter { if (searching) it != ScanCompare.UNKNOWN else it.first }
                    .forEach { c -> Chip(scanLabel(c), m.compare == c, enabled = !m.busy) { m.compare = c } }
            }
            if (m.compare.values >= 1) {
                Field(str(if (m.compare.values == 2) R.string.memory_value_from else R.string.memory_value), m.value1) { m.value1 = it }
            }
            if (m.compare.values == 2) {
                Field(str(R.string.memory_value_to), m.value2) { m.value2 = it }
            }
            if (!searching && s.info.hasTurbo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TipTitle(str(R.string.memory_skip_slow), Tip.MEM_SKIP_SLOW, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = m.skipSlow, onCheckedChange = { m.changeSkipSlow(it) })
                }
            }
            if (!m.scanning) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!searching) {
                        Button(enabled = !m.busy, onClick = { m.firstScan() }) { Text(str(R.string.memory_first_scan)) }
                    } else {
                        Button(enabled = !m.busy, onClick = { m.nextScan() }) { Text(str(R.string.memory_next_scan)) }
                        OutlinedButton(enabled = !m.busy, onClick = { m.newSearch() }) { Text(str(R.string.memory_new_search)) }
                    }
                }
            }
            if (!searching) {
                if (m.compare == ScanCompare.UNKNOWN) {
                    Text(str(R.string.memory_unknown_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                TipTitle(str(R.string.memory_results, m.total, m.scans), Tip.MEM_RESULTS, style = MaterialTheme.typography.labelLarge)
                if (m.keptSearch) {
                    Text(str(R.string.memory_kept_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (m.total > MemorySession.SHOWN) {
                    Text(str(R.string.memory_results_more, MemorySession.SHOWN), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (searching) {
        items(m.hits) { h ->
            val prev = h.previous
            ListRow(
                title = hexAddr(h.address),
                detail = if (prev != null) str(R.string.memory_hit_was, t.format(h.value), t.format(prev)) else t.format(h.value),
                highlight = prev != null && !prev.contentEquals(h.value),
                enabled = !m.busy,
                mono = true
            ) { open(MemoryDialog.Hit(h, t)) }
        }
    }
}

/** A scan type's name; "Unknown value" says what it is for. */
@Composable
private fun scanLabel(c: ScanCompare): String = if (c == ScanCompare.UNKNOWN) str(R.string.memory_scan_unknown) else c.label

// ---------------------------------------------------------------------------------------------
// Cheats
// ---------------------------------------------------------------------------------------------

private fun LazyListScope.cheatItems(s: MemorySnapshot, ctx: Context, open: (MemoryDialog) -> Unit) {
    val m = MemorySession
    val playing = s.game.pid != 0

    // --- ready-made cheats from the cheat files ---
    item {
        Panel {
            TipTitle(str(R.string.memory_cheatfile_title), Tip.MEM_CHEAT_FILES)
            val file = m.cheatFile
            if (file == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = playing && !m.busy, onClick = { m.findCheatFiles() }) { Text(str(R.string.memory_cheatfile_find)) }
                    TextButton(onClick = { open(MemoryDialog.Source) }) { Text(str(R.string.memory_cheatfile_source)) }
                }
                val choices = m.cheatChoices
                if (choices != null && choices.isNotEmpty()) {
                    Text(str(R.string.memory_cheatfile_choose), style = MaterialTheme.typography.bodySmall)
                    choices.forEach { e ->
                        ListRow(
                            title = e.game.ifBlank { e.titleId },
                            detail = str(R.string.memory_cheatfile_version, e.version),
                            highlight = CheatFiles.sameVersion(e.version, s.game.version),
                            enabled = !m.busy
                        ) { m.openCheatFile(e) }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name.ifBlank { file.titleId }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            str(R.string.memory_cheatfile_version, file.version) + if (file.credits.isNotEmpty()) " · " + file.credits.joinToString(", ") else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { m.closeCheatFile() }) { Text(str(R.string.action_close)) }
                }
                file.mods.forEachIndexed { i, mod ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mod.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val note = when {
                                !mod.supported -> str(R.string.memory_mod_unsupported)
                                mod.hint.isNotBlank() -> mod.hint
                                else -> ""
                            }
                            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (mod.button) {
                            TextButton(enabled = playing && mod.supported && !m.busy, onClick = { m.setMod(i, true) }) { Text(str(R.string.memory_mod_apply)) }
                        } else {
                            Switch(checked = i in m.modsOn, onCheckedChange = { m.setMod(i, it) }, enabled = playing && mod.supported && !m.busy)
                        }
                    }
                }
            }
        }
    }

    // --- your own cheats ---
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TipTitle(str(R.string.memory_my_cheats), Tip.MEM_MY_CHEATS)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(enabled = playing, onClick = { open(MemoryDialog.NewCheat(null, m.type, "")) }) { Text(str(R.string.memory_cheat_add)) }
                OutlinedButton(enabled = playing && !m.busy && m.cheats.any { it.signature != null }, onClick = { m.findAgain() }) {
                    Text(str(R.string.memory_find_again_all))
                }
                OutlinedButton(enabled = m.cheats.isNotEmpty(), onClick = { shareText(ctx, m.exportText()) }) { Text(str(R.string.memory_export)) }
                OutlinedButton(onClick = { open(MemoryDialog.Import) }) { Text(str(R.string.memory_import)) }
                TipButton(Tip.MEM_FIND_AGAIN, modifier = Modifier.align(Alignment.CenterVertically))
            }
            if (m.cheats.isEmpty()) {
                Text(str(R.string.memory_cheats_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    for ((group, list) in groupCheats(m.cheats)) {
        if (group.isNotEmpty()) {
            item { Text(group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp)) }
        }
        items(list) { c -> CheatRow(c, playing) { open(MemoryDialog.EditCheat(c)) } }
    }
}

@Composable
private fun CheatRow(c: Cheat, playing: Boolean, onOpen: () -> Unit) {
    val m = MemorySession
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (c.frozen) cs.primary.copy(alpha = 0.10f) else cs.surfaceContainerLow)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                hexAddr(c.address) + " · " + c.type.label + " · " + c.value.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = c.frozen, onCheckedChange = { m.setFrozen(c, it) }, enabled = playing)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(str(R.string.memory_freeze), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                TipButton(Tip.MEM_FREEZE)
            }
        }
    }
}

/** Opens the phone's share sheet with [text]. */
private fun shareText(ctx: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, AppText.get(R.string.memory_export)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        copyText(ctx, "cheats", text, AppText.get(R.string.memory_export_copied))
    }
}

// ---------------------------------------------------------------------------------------------
// Browse
// ---------------------------------------------------------------------------------------------

private fun LazyListScope.browseItems(s: MemorySnapshot, open: (MemoryDialog) -> Unit) {
    val m = MemorySession
    val proc = m.openProc
    if (proc == null) {
        // --- go to, and the process list ---
        if (s.game.pid != 0) {
            item {
                Panel {
                    GoToRow { m.browseTo(it) }
                    OutlinedButton(enabled = !m.busy, onClick = {
                        m.openProcess(s.processes.firstOrNull { it.pid == s.game.pid } ?: DbgProcess("eboot.bin", s.game.pid))
                    }) { Text(str(R.string.memory_open_game)) }
                }
            }
        }
        item { TipTitle(str(R.string.memory_processes, s.processes.size), Tip.MEM_BROWSE, style = MaterialTheme.typography.labelLarge) }
        items(sortProcesses(s.processes, s.game.pid)) { p ->
            ListRow(
                title = p.name.ifBlank { "?" },
                detail = "pid ${p.pid}",
                highlight = p.pid == s.game.pid && p.pid != 0,
                enabled = !m.busy
            ) { m.openProcess(p) }
        }
        return
    }
    if (m.hexAt < 0) {
        // --- one process's memory regions ---
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { m.closeProcess() }) { Text(str(R.string.memory_back)) }
                Text(
                    str(R.string.memory_maps_title, proc.name, proc.pid, m.maps.size),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (m.maps.isEmpty()) {
            item { Text(str(R.string.memory_no_maps), style = MaterialTheme.typography.bodySmall) }
        }
        items(m.maps) { r ->
            ListRow(
                title = r.name.ifBlank { "—" } + "  " + r.protText,
                detail = hexAddr(r.start) + " · " + fmtBytes(r.size),
                highlight = r.writable,
                enabled = !m.busy && r.readable,
                mono = true
            ) { m.showHex(r.start) }
        }
        return
    }
    // --- the bytes at one address ---
    val at = m.hexAt
    val region = m.regionOf(at)
    val data = m.hexData
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { m.closeHex() }) { Text(str(R.string.memory_back)) }
            Text(
                str(R.string.memory_hex_title, region?.name?.ifBlank { "—" } ?: proc.name, hexAddr(at)),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    item {
        Panel {
            GoToRow { m.showHex(it and 0xFL.inv()) }
            if (region == null) {
                Text(str(R.string.memory_outside_regions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(str(R.string.memory_hex_tap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        Panel {
            hexDump(at, data).forEachIndexed { i, line ->
                val lineAt = at + i * 8L
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !m.busy) {
                            open(MemoryDialog.HexLine(lineAt, data.copyOfRange(i * 8, minOf(data.size, i * 8 + 8))))
                        }
                )
            }
        }
    }
    item {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(enabled = !m.busy && at >= 256, onClick = { m.showHex(at - 256) }) { Text(str(R.string.memory_prev)) }
            OutlinedButton(enabled = !m.busy, onClick = { m.showHex(at) }) { Text(str(R.string.memory_refresh)) }
            OutlinedButton(enabled = !m.busy, onClick = { m.showHex(at + 256) }) { Text(str(R.string.memory_next)) }
            if (proc.pid == s.game.pid) {
                OutlinedButton(enabled = !m.busy, onClick = { open(MemoryDialog.NewCheat(at, m.type, "")) }) {
                    Text(str(R.string.memory_cheat_here))
                }
            }
        }
    }
}

/** An address box and a Go button. */
@Composable
private fun GoToRow(onGo: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Field(str(R.string.memory_goto), text) {
                text = it
                bad = false
            }
        }
        Button(enabled = !MemorySession.busy && text.isNotBlank(), onClick = {
            val a = parseAddress(text)
            if (a == null) bad = true else onGo(a)
        }) { Text(str(R.string.memory_go)) }
    }
    if (bad) Text(str(R.string.memory_bad_address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------

@Composable
private fun HitDialog(d: MemoryDialog.Hit, onClose: () -> Unit, onOpen: (MemoryDialog) -> Unit) {
    val m = MemorySession
    val current = d.type.format(d.hit.value)
    var value by remember { mutableStateOf(current) }
    var peek by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(d.hit.address) { peek = m.peek(d.hit.address, 8) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hexAddr(d.hit.address), fontFamily = FontFamily.Monospace) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(str(R.string.memory_hit_now, d.type.label, current), style = MaterialTheme.typography.bodyMedium)
                Field(str(R.string.memory_new_value), value) { value = it }
                AllTypes(peek)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { onOpen(MemoryDialog.NewCheat(d.hit.address, d.type, value)) }) { Text(str(R.string.memory_cheat_add_short)) }
                    TextButton(onClick = {
                        m.watch(d.hit.address, d.type)
                        onClose()
                    }) { Text(str(R.string.memory_watch)) }
                    TextButton(onClick = {
                        onClose()
                        m.browseTo(d.hit.address)
                    }) { Text(str(R.string.memory_show_memory)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !m.busy && d.type.parse(value) != null, onClick = {
                m.writeValue(d.hit.address, d.type, value)
                onClose()
            }) { Text(str(R.string.memory_write)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

/** The 8 bytes at an address read as every kind of value, for when the kind was guessed wrong. */
@Composable
private fun AllTypes(bytes: ByteArray?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(10.dp)
    ) {
        Text(str(R.string.memory_all_types), style = MaterialTheme.typography.labelLarge)
        if (bytes == null) {
            Text("…", style = MaterialTheme.typography.bodySmall)
        } else {
            ValueType.entries.forEach { vt ->
                Row {
                    Text(vt.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(vt.format(bytes), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Text(bytesText(bytes), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Adds a cheat ([new] set) or edits one ([cheat] set). */
@Composable
private fun CheatDialog(cheat: Cheat?, new: MemoryDialog.NewCheat?, onClose: () -> Unit) {
    val m = MemorySession
    var name by remember { mutableStateOf(cheat?.name ?: "") }
    var group by remember { mutableStateOf(cheat?.group ?: m.cheats.lastOrNull()?.group ?: "") }
    var address by remember {
        mutableStateOf((cheat?.address ?: new?.address)?.let { java.lang.Long.toHexString(it).uppercase() } ?: "")
    }
    var type by remember { mutableStateOf(cheat?.type ?: new?.type ?: ValueType.INT) }
    var value by remember { mutableStateOf(cheat?.value ?: new?.value ?: "") }
    val addr = parseAddress(address)
    val valueOk = value.isBlank() || type.parse(value) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(if (cheat == null) R.string.memory_cheat_add else R.string.memory_cheat_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Field(str(R.string.memory_cheat_name), name) { name = it }
                Field(str(R.string.memory_cheat_group), group) { group = it }
                Field(str(R.string.memory_cheat_address), address) { address = it }
                if (addr == null && address.isNotBlank()) {
                    Text(str(R.string.memory_bad_address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                ChipRow {
                    ValueType.entries.forEach { t -> Chip(t.label, type == t) { type = t } }
                }
                Field(str(R.string.memory_value), value) { value = it }
                if (!valueOk) {
                    Text(str(R.string.memory_bad_value, type.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (cheat != null) {
                    Text(
                        str(if (cheat.signature != null) R.string.memory_cheat_findable else R.string.memory_cheat_not_findable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(enabled = !m.busy && addr != null && value.isNotBlank() && valueOk, onClick = {
                        if (addr != null) m.writeValue(addr, type, value)
                    }) { Text(str(R.string.memory_write_now)) }
                    if (cheat != null) {
                        if (cheat.signature != null) {
                            TextButton(enabled = !m.busy, onClick = {
                                m.findAgain(cheat)
                                onClose()
                            }) { Text(str(R.string.memory_find_again)) }
                        } else {
                            TextButton(enabled = !m.busy, onClick = {
                                m.captureSignature(cheat)
                                onClose()
                            }) { Text(str(R.string.memory_make_findable)) }
                        }
                        TextButton(onClick = {
                            m.watch(cheat.address, cheat.type)
                            onClose()
                        }) { Text(str(R.string.memory_watch)) }
                        TextButton(onClick = {
                            m.removeCheat(cheat)
                            onClose()
                        }) { Text(str(R.string.action_remove)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = addr != null && valueOk, onClick = {
                if (addr != null) {
                    if (cheat == null) {
                        m.addCheat(name, addr, type, value, group)
                    } else {
                        val moved = addr != cheat.address || type != cheat.type
                        val c = cheat.copy(name = name.trim().ifBlank { cheat.name }, address = addr, type = type, value = value.trim(), group = group.trim())
                        // A cheat moved by hand no longer matches its old signature
                        m.updateCheat(if (moved) c.copy(sigHex = "", sigMask = "", sigAt = 0) else c)
                    }
                }
                onClose()
            }) { Text(str(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

/** Edits the bytes of one line of the hex view. */
@Composable
private fun HexLineDialog(d: MemoryDialog.HexLine, onClose: () -> Unit) {
    val m = MemorySession
    var text by remember { mutableStateOf(bytesText(d.bytes)) }
    val parsed = CheatFiles.hex(text)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hexAddr(d.address), fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_hex_edit_help), style = MaterialTheme.typography.bodySmall)
                Field(str(R.string.memory_hex_bytes), text) { text = it }
                if (parsed == null || parsed.isEmpty()) {
                    Text(str(R.string.memory_hex_bad), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                AllTypes(d.bytes.copyOf(8))
            }
        },
        confirmButton = {
            TextButton(enabled = !m.busy && parsed != null && parsed.isNotEmpty(), onClick = {
                if (parsed != null) m.writeBytes(d.address, parsed, inOpen = true)
                onClose()
            }) { Text(str(R.string.memory_write)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

@Composable
private fun WatchDialog(w: Watch, onClose: () -> Unit, onOpen: (MemoryDialog) -> Unit) {
    val m = MemorySession
    val now = w.value?.let { w.type.format(it) } ?: ""
    var value by remember { mutableStateOf(now) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hexAddr(w.address), fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_hit_now, w.type.label, now.ifBlank { "…" }), style = MaterialTheme.typography.bodyMedium)
                Field(str(R.string.memory_new_value), value) { value = it }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { onOpen(MemoryDialog.NewCheat(w.address, w.type, value)) }) { Text(str(R.string.memory_cheat_add_short)) }
                    TextButton(onClick = {
                        m.unwatch(w)
                        onClose()
                    }) { Text(str(R.string.memory_unwatch)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !m.busy && w.type.parse(value) != null, onClick = {
                m.writeValue(w.address, w.type, value)
                onClose()
            }) { Text(str(R.string.memory_write)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

@Composable
private fun ImportDialog(ctx: Context, onClose: () -> Unit) {
    val m = MemorySession
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.memory_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_import_help), style = MaterialTheme.typography.bodySmall)
                LinesField(str(R.string.memory_import_box), text, 4) { text = it }
                TextButton(onClick = { text = clipboardText(ctx) }) { Text(str(R.string.install_paste)) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                m.importText(text)
                onClose()
            }) { Text(str(R.string.memory_import)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

@Composable
private fun SourceDialog(onClose: () -> Unit) {
    val m = MemorySession
    var text by remember { mutableStateOf(m.cheatSource) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.memory_cheatfile_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_cheatfile_source_help), style = MaterialTheme.typography.bodySmall)
                Field(str(R.string.memory_cheatfile_source_url), text) { text = it }
                TextButton(onClick = { text = CheatFiles.DEFAULT_SOURCE }) { Text(str(R.string.memory_cheatfile_source_reset)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                m.changeCheatSource(text)
                onClose()
            }) { Text(str(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

// ---------------------------------------------------------------------------------------------
// Small parts
// ---------------------------------------------------------------------------------------------

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

/** A rounded button that shows whether it is the chosen one. */
@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            selected -> cs.onPrimary
            enabled -> cs.onSurface
            else -> cs.onSurfaceVariant.copy(alpha = 0.6f)
        },
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/** A rounded box in the card color, like the console card at the top. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

/** One tappable line of a list: a name and a smaller detail under it. */
@Composable
private fun ListRow(title: String, detail: String, highlight: Boolean, enabled: Boolean, mono: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlight) cs.primary.copy(alpha = 0.10f) else cs.surfaceContainerLow)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = cs.onSurfaceVariant,
            maxLines = 1
        )
    }
}
