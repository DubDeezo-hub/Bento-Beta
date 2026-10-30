package com.example.pssender

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

// ---------------------------------------------------------------------------------------------
// The console's log: ps5debug-NG sends the kernel log (what the system and payloads print) to anyone who connects
// to port 3232. Useful when a payload or a cheat misbehaves. Shown on the Memory tab under "Console log".
// ---------------------------------------------------------------------------------------------

/** Splits a stream of bytes into lines (UTF-8 is decoded per line, so no letter is cut in two). */
class LineSplitter(private val maxLine: Int = 4000) {
    private val buf = java.io.ByteArrayOutputStream()

    fun feed(data: ByteArray, n: Int, out: (String) -> Unit) {
        for (i in 0 until n) {
            val b = data[i]
            if (b == '\n'.code.toByte()) {
                out(take())
            } else {
                buf.write(b.toInt())
                if (buf.size() >= maxLine) out(take())
            }
        }
    }

    private fun take(): String = String(buf.toByteArray(), Charsets.UTF_8).trimEnd('\r').also { buf.reset() }

    /** What is left after the last line break. */
    fun rest(): String = take()
}

object KernelLog {
    const val DEFAULT_PORT = 3232
    /** Lines kept; older ones go. */
    const val MAX_LINES = 4000
    private const val PORT_KEY = "klog_port"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var socket: Socket? = null

    val lines = mutableStateListOf<String>()
    /** Lines that came in while paused; shown on Resume. */
    private val held = ArrayList<String>()

    var running by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var filter by mutableStateOf("")
    var port by mutableStateOf(DEFAULT_PORT.toString())
        private set
    /** Keep the newest line in sight. */
    var follow by mutableStateOf(true)

    private var loaded = false

    fun attach(ctx: Context) {
        if (loaded) return
        loaded = true
        port = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PORT_KEY, DEFAULT_PORT.toString()) ?: DEFAULT_PORT.toString()
    }

    fun changePort(ctx: Context, v: String) {
        port = v.filter { it.isDigit() }.take(5)
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(PORT_KEY, port).apply()
    }

    /** The lines that match the filter (all of them when it is empty). */
    fun shown(): List<String> {
        val f = filter.trim()
        return if (f.isEmpty()) lines else lines.filter { it.contains(f, ignoreCase = true) }
    }

    fun start(host: String) {
        if (running) return
        val p = port.toIntOrNull()?.takeIf { it in 1..65535 }
        if (host.isBlank()) {
            status = AppText.get(R.string.memory_need_ip)
            return
        }
        if (p == null) {
            status = AppText.get(R.string.memory_bad_port)
            return
        }
        running = true
        status = AppText.get(R.string.klog_connecting)
        job = scope.launch {
            val pending = ArrayList<String>()
            val lock = Any()
            // Lines are handed to the screen a few times a second, not one by one
            val flusher = launch {
                while (isActive) {
                    delay(250)
                    val batch = synchronized(lock) { ArrayList(pending).also { pending.clear() } }
                    if (batch.isNotEmpty()) add(batch)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    val s = Socket()
                    socket = s
                    s.connect(InetSocketAddress(host, p), 5000)
                    s.soTimeout = 1000
                    withContext(Dispatchers.Main) { status = AppText.get(R.string.klog_receiving, host, p) }
                    val input: InputStream = s.getInputStream()
                    val buf = ByteArray(16 * 1024)
                    val split = LineSplitter()
                    while (isActive) {
                        val n = try { input.read(buf) } catch (_: SocketTimeoutException) { continue }
                        if (n < 0) break
                        split.feed(buf, n) { line -> synchronized(lock) { pending.add(line) } }
                    }
                    val last = split.rest()
                    if (last.isNotEmpty()) synchronized(lock) { pending.add(last) }
                }
                status = AppText.get(R.string.klog_closed)
            } catch (e: Exception) {
                if (running) {
                    status = when (e) {
                        is java.net.ConnectException, is SocketTimeoutException, is java.net.NoRouteToHostException ->
                            AppText.get(R.string.klog_failed, p)
                        else -> e.message ?: e.javaClass.simpleName
                    }
                }
            } finally {
                flusher.cancel()
                val batch = synchronized(lock) { ArrayList(pending).also { pending.clear() } }
                if (batch.isNotEmpty()) add(batch)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                running = false
            }
        }
    }

    fun stop() {
        running = false
        status = AppText.get(R.string.klog_stopped)
        try { socket?.close() } catch (_: Exception) {}
        job?.cancel()
        job = null
    }

    fun togglePause() {
        paused = !paused
        if (!paused && held.isNotEmpty()) {
            val h = ArrayList(held)
            held.clear()
            add(h)
        }
    }

    fun clear() {
        lines.clear()
        held.clear()
    }

    fun text(): String = shown().joinToString("\n")

    private fun add(batch: List<String>) {
        if (paused) {
            held.addAll(batch)
            if (held.size > MAX_LINES) held.subList(0, held.size - MAX_LINES).clear()
            return
        }
        lines.addAll(batch)
        val over = lines.size - MAX_LINES
        if (over > 0) lines.removeRange(0, over)
    }
}

/** The "Console log" view of the Memory tab. */
fun LazyListScope.logItems(host: String, ctx: Context) {
    val k = KernelLog
    item {
        Panel {
            TipTitle(str(R.string.klog_title), Tip.MEM_LOG)
            Text(str(R.string.klog_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!k.running) {
                NumberField(str(R.string.klog_port), k.port) { k.changePort(ctx, it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (k.running) {
                    Button(onClick = { k.stop() }) { Text(str(R.string.klog_stop)) }
                    OutlinedButton(onClick = { k.togglePause() }) { Text(str(if (k.paused) R.string.klog_resume else R.string.klog_pause)) }
                } else {
                    Button(onClick = { k.start(host) }) { Text(str(R.string.klog_start)) }
                }
            }
            if (k.status.isNotEmpty()) {
                Text(k.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Field(str(R.string.klog_filter), k.filter) { k.filter = it }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = k.lines.isNotEmpty(), onClick = {
                    copyText(ctx, "log", k.text(), AppText.get(R.string.help_copied))
                }) { Text(str(R.string.klog_copy)) }
                TextButton(enabled = k.lines.isNotEmpty(), onClick = { k.clear() }) { Text(str(R.string.action_clear)) }
                TextButton(onClick = { k.follow = !k.follow }) {
                    Text(str(if (k.follow) R.string.klog_follow_on else R.string.klog_follow_off))
                }
            }
        }
    }
    val shown = k.shown()
    if (shown.isEmpty()) {
        item {
            Text(
                str(if (k.lines.isEmpty()) R.string.klog_empty else R.string.klog_no_match),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
    items(shown) { line ->
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}
