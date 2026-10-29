package com.example.pssender // change to match your project's package name

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
// Three parts: Scan (find a value, narrow it down, change it), Cheats (saved addresses, set or freeze them) and
// Browse (processes, memory regions and a hex view). The state lives in MemorySession, so it survives tab switches.
// ---------------------------------------------------------------------------------------------

/** The debugger port is kept for the whole app (it is 744 on every PS5; the fake console uses 7744). */
object MemoryPrefs {
    private const val PORT = "debug_port"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun port(ctx: Context): String = prefs(ctx).getString(PORT, DEBUG_PORT.toString()) ?: DEBUG_PORT.toString()
    fun setPort(ctx: Context, v: String) = prefs(ctx).edit().putString(PORT, v).apply()
}

/** The game first, then the rest by process number. */
fun sortProcesses(list: List<DbgProcess>, gamePid: Int): List<DbgProcess> =
    list.sortedWith(compareBy<DbgProcess>({ if (it.pid == gamePid && gamePid != 0) 0 else 1 }, { it.pid }))

/** A match the user tapped, or a cheat being added or edited. */
private sealed class MemoryDialog {
    class Hit(val hit: MemoryHit, val type: ValueType) : MemoryDialog()
    class EditCheat(val cheat: Cheat) : MemoryDialog()
    class NewCheat(val address: Long?, val type: ValueType, val value: String) : MemoryDialog()
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

    // While the matches fit on the list, keep their values live
    val results = m.results
    LaunchedEffect(results, m.view) {
        if (results != null && results.total <= MemorySession.SHOWN && m.view == MemoryView.SCAN) {
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
                MemoryView.CHEATS -> cheatItems(s) { dialog = it }
                MemoryView.BROWSE -> browseItems(s) { dialog = it }
            }
        }
    }

    when (val d = dialog) {
        is MemoryDialog.Hit -> HitDialog(d, onClose = { dialog = null }, onAddCheat = { dialog = it })
        is MemoryDialog.EditCheat -> CheatDialog(d.cheat, null) { dialog = null }
        is MemoryDialog.NewCheat -> CheatDialog(null, d) { dialog = null }
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
            NumberField(str(R.string.memory_port), m.port) { m.changePort(it) }
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
        if (s == null) {
            Text(str(R.string.memory_intro), style = MaterialTheme.typography.bodySmall)
            Text(str(R.string.memory_test_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Scan
// ---------------------------------------------------------------------------------------------

private fun LazyListScope.scanItems(s: MemorySnapshot, open: (MemoryDialog) -> Unit) {
    val m = MemorySession
    val r = m.results
    if (s.game.pid == 0) {
        item { Text(str(R.string.memory_scan_needs_game), style = MaterialTheme.typography.bodySmall) }
        return
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The kind of value is fixed for a search; a new search can change it
            Text(str(R.string.memory_value_type), style = MaterialTheme.typography.labelLarge)
            ChipRow {
                ValueType.entries.forEach { t ->
                    val chosen = (r?.type ?: m.type) == t
                    Chip(t.label, chosen, enabled = r == null && !m.busy) { m.type = t }
                }
            }
            Text(str(R.string.memory_scan_type), style = MaterialTheme.typography.labelLarge)
            ChipRow {
                ScanCompare.entries.filter { r != null || it.first }.forEach { c ->
                    Chip(c.label, m.compare == c, enabled = !m.busy) { m.compare = c }
                }
            }
            if (m.compare.values >= 1) {
                Field(str(if (m.compare.values == 2) R.string.memory_value_from else R.string.memory_value), m.value1) { m.value1 = it }
            }
            if (m.compare.values == 2) {
                Field(str(R.string.memory_value_to), m.value2) { m.value2 = it }
            }
            if (m.scanning) {
                val all = m.progressAll
                LinearProgressIndicator(
                    progress = { if (all > 0) (m.progressDone.toFloat() / all).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { m.cancel() }) { Text(str(R.string.action_cancel)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r == null) {
                        Button(enabled = !m.busy, onClick = { m.firstScan() }) { Text(str(R.string.memory_first_scan)) }
                    } else {
                        Button(enabled = !m.busy, onClick = { m.nextScan() }) { Text(str(R.string.memory_next_scan)) }
                        OutlinedButton(enabled = !m.busy, onClick = { m.newSearch() }) { Text(str(R.string.memory_new_search)) }
                    }
                }
            }
            if (r == null) {
                Text(str(R.string.memory_scan_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(str(R.string.memory_results, r.total, r.scans), style = MaterialTheme.typography.labelLarge)
                if (r.total > MemorySession.SHOWN) {
                    Text(str(R.string.memory_results_more, MemorySession.SHOWN), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (r != null) {
        items(m.hits) { h ->
            ListRow(
                title = hexAddr(h.address),
                detail = if (h.previous != null) str(R.string.memory_hit_was, r.type.format(h.value), r.type.format(h.previous))
                else r.type.format(h.value),
                highlight = h.previous != null && !h.previous.contentEquals(h.value),
                enabled = !m.busy,
                mono = true
            ) { open(MemoryDialog.Hit(h, r.type)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cheats
// ---------------------------------------------------------------------------------------------

private fun LazyListScope.cheatItems(s: MemorySnapshot, open: (MemoryDialog) -> Unit) {
    val m = MemorySession
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = s.game.pid != 0, onClick = { open(MemoryDialog.NewCheat(null, m.type, "")) }) {
                Text(str(R.string.memory_cheat_add))
            }
            Text(
                str(if (m.cheats.isEmpty()) R.string.memory_cheats_empty else R.string.memory_cheats_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    items(m.cheats) { c ->
        val cs = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (c.frozen) cs.primary.copy(alpha = 0.10f) else cs.surfaceContainerLow)
                .clickable { open(MemoryDialog.EditCheat(c)) }
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
                Switch(checked = c.frozen, onCheckedChange = { m.setFrozen(c, it) }, enabled = s.game.pid != 0)
                Text(str(R.string.memory_freeze), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
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
        item { Text(str(R.string.memory_processes, s.processes.size), style = MaterialTheme.typography.labelLarge) }
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
        }
    }
    item {
        Panel {
            hexDump(at, m.hexData).forEach { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, softWrap = false)
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
private fun HitDialog(d: MemoryDialog.Hit, onClose: () -> Unit, onAddCheat: (MemoryDialog) -> Unit) {
    val m = MemorySession
    val current = d.type.format(d.hit.value)
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hexAddr(d.hit.address), fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_hit_now, d.type.label, current), style = MaterialTheme.typography.bodyMedium)
                Field(str(R.string.memory_new_value), value) { value = it }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onAddCheat(MemoryDialog.NewCheat(d.hit.address, d.type, value)) }) {
                        Text(str(R.string.memory_cheat_add_short))
                    }
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

/** Adds a cheat ([new] set) or edits one ([cheat] set). */
@Composable
private fun CheatDialog(cheat: Cheat?, new: MemoryDialog.NewCheat?, onClose: () -> Unit) {
    val m = MemorySession
    var name by remember { mutableStateOf(cheat?.name ?: "") }
    var address by remember { mutableStateOf(cheat?.address?.let { java.lang.Long.toHexString(it).uppercase() } ?: new?.address?.let { java.lang.Long.toHexString(it).uppercase() } ?: "") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !m.busy && addr != null && value.isNotBlank() && valueOk, onClick = {
                        if (addr != null) m.writeValue(addr, type, value)
                    }) { Text(str(R.string.memory_write_now)) }
                    if (cheat != null) {
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
                    if (cheat == null) m.addCheat(name, addr, type, value)
                    else m.updateCheat(cheat.copy(name = name.trim().ifBlank { cheat.name }, address = addr, type = type, value = value.trim()))
                }
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
