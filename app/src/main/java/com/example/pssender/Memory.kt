package com.example.pssender // change to match your project's package name

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

// ---------------------------------------------------------------------------------------------
// The Memory tab: a memory viewer for games on the console, through ps5debug-NG (see Ps5Debug.kt).
// Stage 1 only reads: it connects, shows the debugger and firmware, the current game and the process list, and lets
// you open a process's memory regions and look at their bytes. Searching and changing values come next.
// ---------------------------------------------------------------------------------------------

/** How many bytes the hex view shows at a time. */
private const val HEX_PAGE = 256

/** The debugger port is kept for the whole app (it is 744 on every PS5; the fake console uses 7744). */
object MemoryPrefs {
    private const val PORT = "debug_port"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun port(ctx: Context): String = prefs(ctx).getString(PORT, DEBUG_PORT.toString()) ?: DEBUG_PORT.toString()
    fun setPort(ctx: Context, v: String) = prefs(ctx).edit().putString(PORT, v).apply()
}

/** What one tap of Connect brings back. */
private class MemorySnapshot(val info: DbgInfo, val game: DbgApp, val processes: List<DbgProcess>)

/** The game first, then the rest by process number. */
fun sortProcesses(list: List<DbgProcess>, gamePid: Int): List<DbgProcess> =
    list.sortedWith(compareBy<DbgProcess>({ if (it.pid == gamePid && gamePid != 0) 0 else 1 }, { it.pid }))

/** A message for a connection that failed: a missing debugger gets advice, anything else the usual "Failed: ...". */
private fun memoryError(e: Exception, port: Int): String = when (e) {
    is ConnectException, is SocketTimeoutException, is NoRouteToHostException -> AppText.get(R.string.memory_err_no_debugger, port)
    else -> failedText(e)
}

@Composable
fun MemoryTab(host: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var port by remember { mutableStateOf(MemoryPrefs.port(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<MemorySnapshot?>(null) }
    var openProc by remember { mutableStateOf<DbgProcess?>(null) }
    var maps by remember { mutableStateOf<List<DbgMap>>(emptyList()) }
    var openMap by remember { mutableStateOf<DbgMap?>(null) }
    var hexAt by remember { mutableStateOf(0L) }
    var hexLines by remember { mutableStateOf<List<String>>(emptyList()) }

    /** Runs [work] on a fresh connection in the background, then [done] with its result on the screen. */
    fun <T> ask(work: (Ps5Debug) -> T, done: (T) -> Unit) {
        val h = host.trim()
        val p = port.trim().toIntOrNull()
        if (h.isEmpty()) {
            status = AppText.get(R.string.memory_need_ip)
            return
        }
        if (p == null || p !in 1..65535) {
            status = AppText.get(R.string.memory_bad_port)
            return
        }
        busy = true
        status = AppText.get(R.string.memory_loading)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { withDebugger(h, p, work) }
                status = ""
                done(result)
            } catch (e: Exception) {
                status = memoryError(e, p)
            } finally {
                busy = false
            }
        }
    }

    fun connect() = ask({ d -> MemorySnapshot(d.info(), d.foregroundApp(), d.processes()) }) { s ->
        snapshot = s
        openProc = null
        openMap = null
    }

    fun openProcess(p: DbgProcess) = ask({ d -> d.maps(p.pid) }) { m ->
        maps = m
        openProc = p
        openMap = null
    }

    fun showHex(p: DbgProcess, m: DbgMap, at: Long) {
        val from = at.coerceIn(m.start, maxOf(m.start, m.end - 1))
        val len = minOf(HEX_PAGE.toLong(), m.end - from).toInt()
        ask({ d -> d.read(p.pid, from, len) }) { bytes ->
            openMap = m
            hexAt = from
            hexLines = hexDump(from, bytes)
        }
    }

    // Back steps out of the hex view, then out of the process, before it leaves the tab
    BackHandler(enabled = openMap != null || openProc != null) {
        if (openMap != null) openMap = null else openProc = null
    }

    val s = snapshot
    val proc = openProc
    val region = openMap
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(str(R.string.memory_port), port) { v ->
                    port = v
                    MemoryPrefs.setPort(ctx, v)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy, onClick = { connect() }) {
                        Text(str(if (s == null) R.string.memory_connect else R.string.memory_refresh))
                    }
                    if (s != null) {
                        Text(
                            str(R.string.memory_connected, s.info.brand, s.info.firmware),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    HelpLink(status)
                }
                if (s == null) {
                    Text(str(R.string.memory_intro), style = MaterialTheme.typography.bodySmall)
                    Text(str(R.string.memory_test_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (s != null && proc == null) {
            // --- the current game and the process list ---
            item {
                Panel {
                    Text(str(R.string.memory_game_title), style = MaterialTheme.typography.labelLarge)
                    if (s.game.pid == 0) {
                        Text(str(R.string.memory_no_game), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(s.game.name.ifBlank { s.game.titleId }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            str(R.string.memory_game_detail, s.game.titleId, s.game.version.ifBlank { "?" }, s.game.pid),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(enabled = !busy, onClick = {
                            val p = s.processes.firstOrNull { it.pid == s.game.pid } ?: DbgProcess("eboot.bin", s.game.pid)
                            openProcess(p)
                        }) { Text(str(R.string.memory_open_game)) }
                    }
                }
            }
            item {
                Text(str(R.string.memory_processes, s.processes.size), style = MaterialTheme.typography.labelLarge)
            }
            items(sortProcesses(s.processes, s.game.pid)) { p ->
                ListRow(
                    title = p.name.ifBlank { "?" },
                    detail = "pid ${p.pid}",
                    highlight = p.pid == s.game.pid && p.pid != 0,
                    enabled = !busy
                ) { openProcess(p) }
            }
        }

        if (proc != null && region == null) {
            // --- one process's memory regions ---
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openProc = null }) { Text(str(R.string.memory_back)) }
                    Text(
                        str(R.string.memory_maps_title, proc.name, proc.pid, maps.size),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (maps.isEmpty()) {
                item { Text(str(R.string.memory_no_maps), style = MaterialTheme.typography.bodySmall) }
            }
            items(maps) { m ->
                ListRow(
                    title = m.name.ifBlank { "—" } + "  " + m.protText,
                    detail = hexAddr(m.start) + " · " + fmtBytes(m.size),
                    highlight = m.writable,
                    enabled = !busy && m.readable,
                    mono = true
                ) { showHex(proc, m, m.start) }
            }
        }

        if (proc != null && region != null) {
            // --- the bytes of one region ---
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openMap = null }) { Text(str(R.string.memory_back)) }
                    Text(
                        str(R.string.memory_hex_title, region.name.ifBlank { "—" }, hexAddr(hexAt)),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            item {
                Panel {
                    hexLines.forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && hexAt > region.start, onClick = { showHex(proc, region, hexAt - HEX_PAGE) }) {
                        Text(str(R.string.memory_prev))
                    }
                    OutlinedButton(enabled = !busy, onClick = { showHex(proc, region, hexAt) }) {
                        Text(str(R.string.memory_refresh))
                    }
                    OutlinedButton(enabled = !busy && hexAt + HEX_PAGE < region.end, onClick = { showHex(proc, region, hexAt + HEX_PAGE) }) {
                        Text(str(R.string.memory_next))
                    }
                }
            }
        }
    }
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
