package com.example.pssender // change to match your project's package name

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// What the Memory tab knows while the app runs: the connection details, the current game, the search and its
// results, the open process in Browse, and the cheats with their freeze loop. It lives outside the screen, so
// switching tabs or turning the phone keeps everything (and frozen values stay frozen).
// ---------------------------------------------------------------------------------------------

/** A saved address: a name, where it is, what kind of value, and the value to write or keep there. */
data class Cheat(
    val id: String,
    val name: String,
    val address: Long,
    val type: ValueType,
    val value: String,
    val frozen: Boolean = false
)

/** The cheats of each game, saved by title ID in the app's settings. (Frozen is not saved: a game starts unfrozen.) */
object CheatStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("memory_cheats", Context.MODE_PRIVATE)
    private fun key(titleId: String) = "t_" + titleId.ifBlank { "unknown" }

    fun load(ctx: Context, titleId: String): List<Cheat> = parseCheats(prefs(ctx).getString(key(titleId), null))

    fun save(ctx: Context, titleId: String, list: List<Cheat>) {
        prefs(ctx).edit().putString(key(titleId), cheatsToJson(list)).apply()
    }
}

fun cheatsToJson(list: List<Cheat>): String {
    val arr = JSONArray()
    for (c in list) {
        arr.put(
            JSONObject().put("id", c.id).put("name", c.name).put("address", java.lang.Long.toHexString(c.address))
                .put("type", c.type.name).put("value", c.value)
        )
    }
    return arr.toString()
}

fun parseCheats(json: String?): List<Cheat> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val addr = java.lang.Long.parseUnsignedLong(o.optString("address", ""), 16)
            Cheat(
                id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", ""),
                address = addr,
                type = ValueType.of(o.optString("type", "")),
                value = o.optString("value", "")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** An address typed by the user: hex, with or without 0x. Null when it is not one. */
fun parseAddress(text: String): Long? {
    val t = text.trim().removePrefix("0x").removePrefix("0X").replace(" ", "").replace("_", "")
    if (t.isEmpty() || t.length > 16) return null
    return try {
        java.lang.Long.parseUnsignedLong(t, 16)
    } catch (_: NumberFormatException) {
        null
    }
}

/** The three parts of the Memory tab. */
enum class MemoryView { SCAN, CHEATS, BROWSE }

/** What one tap of Connect brings back. */
class MemorySnapshot(val info: DbgInfo, val game: DbgApp, val processes: List<DbgProcess>)

object MemorySession {
    /** How many matches the list shows (and refreshes). */
    const val SHOWN = 200

    /** Matches are re-read this often while the list is short enough to show them all. */
    const val LIVE_MS = 1500L

    /** A frozen value is written again this often. */
    private const val FREEZE_MS = 200L

    /** How long a scan may wait for the console: a big region with few matches sends nothing for a while. */
    private const val SCAN_TIMEOUT_MS = 300_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null

    // --- connection and console ---
    var port by mutableStateOf(DEBUG_PORT.toString())
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
    var snapshot by mutableStateOf<MemorySnapshot?>(null)
        private set
    var view by mutableStateOf(MemoryView.SCAN)

    // --- search ---
    var type by mutableStateOf(ValueType.INT)
    var compare by mutableStateOf(ScanCompare.EXACT)
    var value1 by mutableStateOf("")
    var value2 by mutableStateOf("")
    var results by mutableStateOf<ScanResults?>(null)
        private set
    var hits by mutableStateOf<List<ScanHit>>(emptyList())
        private set
    var progressDone by mutableStateOf(0L)
        private set
    var progressAll by mutableStateOf(0L)
        private set
    var scanning by mutableStateOf(false)
        private set

    // --- browse ---
    var openProc by mutableStateOf<DbgProcess?>(null)
    var maps by mutableStateOf<List<DbgMap>>(emptyList())
        private set
    var hexAt by mutableStateOf(-1L)
        private set
    var hexData by mutableStateOf(ByteArray(0))
        private set

    // --- cheats ---
    var cheats by mutableStateOf<List<Cheat>>(emptyList())
        private set

    @Volatile
    private var current: Ps5Debug? = null

    @Volatile
    private var cancelRequested = false

    private var freezeJob: Job? = null

    @Volatile
    private var freezeTargets: List<Pair<Long, ByteArray>> = emptyList()

    private var host = ""

    /** Call once from the screen, so the session can save settings and cheats. */
    fun attach(ctx: Context) {
        if (app == null) {
            app = ctx.applicationContext
            port = MemoryPrefs.port(ctx)
        }
    }

    fun setHost(h: String) {
        if (h.trim() != host) {
            host = h.trim()
            stopFreezing()
        }
    }

    fun changePort(v: String) {
        port = v
        app?.let { MemoryPrefs.setPort(it, v) }
    }

    /** The pid scans, cheats and Go to work on: the game in the foreground. */
    val gamePid: Int get() = snapshot?.game?.pid ?: 0
    private val titleId: String get() = snapshot?.game?.titleId ?: ""

    // --- running things on the console ---

    private fun portOrNull(): Int? = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    private fun errorText(e: Exception, p: Int): String = when {
        cancelRequested -> AppText.get(R.string.memory_cancelled)
        e is TooManyResults -> AppText.get(R.string.memory_too_many, e.limit)
        e is ConnectException || e is SocketTimeoutException || e is NoRouteToHostException ->
            AppText.get(R.string.memory_err_no_debugger, p)
        else -> failedText(e)
    }

    /**
     * Runs [work] on a fresh connection in the background, then [done] with its result here. Only one at a time;
     * the buttons are off meanwhile. [scan] connections get a long timeout and the scan unlock.
     */
    private fun <T> perform(scan: Boolean = false, work: (Ps5Debug) -> T, done: (T) -> Unit) {
        if (busy) return
        val h = host
        val p = portOrNull()
        if (h.isEmpty()) {
            status = AppText.get(R.string.memory_need_ip)
            return
        }
        if (p == null) {
            status = AppText.get(R.string.memory_bad_port)
            return
        }
        busy = true
        cancelRequested = false
        status = AppText.get(if (scan) R.string.memory_scanning else R.string.memory_loading)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Ps5Debug(h, p).use { d ->
                        current = d
                        if (scan) {
                            d.setTimeout(SCAN_TIMEOUT_MS)
                            d.unlockScans()
                        }
                        work(d)
                    }
                }
                status = ""
                done(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = errorText(e, p)
            } finally {
                current = null
                busy = false
                scanning = false
            }
        }
    }

    /** Stops a running scan: the connection is closed, which ends the wait at once. */
    fun cancel() {
        cancelRequested = true
        try { current?.close() } catch (_: Exception) {}
    }

    // --- connect ---

    fun connect() = perform(work = { d ->
        val info = d.info()
        val procs = d.processes()
        // Only ps5debug-NG knows the foreground app; with an older debugger the game is the eboot.bin process
        val game = if (info.brand.contains("ps5debug-NG", ignoreCase = true)) {
            d.foregroundApp()
        } else {
            val e = procs.firstOrNull { it.name.equals("eboot.bin", ignoreCase = true) }
            DbgApp(e?.pid ?: 0, "", "", e?.name ?: "", "")
        }
        MemorySnapshot(info, game, procs)
    }) { s ->
        val before = snapshot?.game
        snapshot = s
        if (before == null || before.pid != s.game.pid || before.titleId != s.game.titleId) {
            // A different game (or a restart of it): its old search and open process no longer apply
            stopFreezing()
            newSearch()
            openProc = null
            hexAt = -1L
            cheats = app?.let { CheatStore.load(it, s.game.titleId) } ?: emptyList()
        }
    }

    // --- search ---

    fun newSearch() {
        results = null
        hits = emptyList()
        progressDone = 0
        progressAll = 0
        if (!compare.first) compare = ScanCompare.EXACT
    }

    /** The value(s) typed, as bytes for the scan; sets the status and gives null when they are missing or wrong. */
    private fun scanBytes(t: ValueType): ByteArray? {
        val data = Scanner.scanData(t, compare, value1, value2)
        if (data == null) status = AppText.get(R.string.memory_bad_value, t.label)
        return data
    }

    private fun progress(done: Long, all: Long) {
        scope.launch {
            progressDone = done
            progressAll = all
        }
    }

    fun firstScan() {
        val pid = gamePid
        if (pid == 0) {
            status = AppText.get(R.string.memory_no_game)
            return
        }
        val t = type
        val cmp = compare
        val data = scanBytes(t) ?: return
        scanning = true
        perform(scan = true, work = { d ->
            val regions = Scanner.searchable(d.maps(pid))
            Scanner.first(d, pid, regions, t, cmp, data, ::progress) { cancelRequested }
        }) { r ->
            results = r
            hits = r.hits(SHOWN)
            compare = ScanCompare.EXACT
            status = AppText.get(R.string.memory_found, r.total)
        }
        if (!busy) scanning = false
    }

    fun nextScan() {
        val prev = results ?: return
        val cmp = compare
        val data = scanBytes(prev.type) ?: return
        scanning = true
        perform(scan = true, work = { d -> Scanner.next(d, prev, cmp, data, ::progress) { cancelRequested } }) { r ->
            results = r
            hits = r.hits(SHOWN, prev)
            status = AppText.get(R.string.memory_found, r.total)
        }
        if (!busy) scanning = false
    }

    /** Re-reads the shown matches without blocking the screen; errors are ignored (the next try may work). */
    fun refreshQuietly() {
        val r = results ?: return
        val shown = hits
        val h = host
        val p = portOrNull() ?: return
        if (scanning || shown.isEmpty() || h.isEmpty()) return
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    Ps5Debug(h, p, timeoutMs = 4000).use { d ->
                        d.unlockScans()
                        Scanner.refresh(d, r.pid, r.type, shown)
                    }
                }
                // Keep the previous value of the scan, so "was" stays what it was at the last scan
                if (results === r && hits === shown) hits = fresh.mapIndexed { i, f -> ScanHit(f.address, f.value, shown[i].previous) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // --- writing values ---

    /** Writes [text] as a [t] value at [address] in the game once. */
    fun writeValue(address: Long, t: ValueType, text: String, onDone: () -> Unit = {}) {
        val pid = gamePid
        val bytes = t.parse(text)
        if (pid == 0) {
            status = AppText.get(R.string.memory_no_game)
            return
        }
        if (bytes == null) {
            status = AppText.get(R.string.memory_bad_value, t.label)
            return
        }
        perform(work = { d -> d.write(pid, address, bytes) }) {
            status = AppText.get(R.string.memory_written, t.format(bytes), hexAddr(address))
            onDone()
            refreshQuietly()
        }
    }

    // --- cheats ---

    private fun saveCheats(list: List<Cheat>) {
        cheats = list
        app?.let { CheatStore.save(it, titleId, list) }
        updateFreezing()
    }

    fun addCheat(name: String, address: Long, t: ValueType, value: String) {
        val n = name.trim().ifBlank { AppText.get(R.string.memory_cheat_default, cheats.size + 1) }
        saveCheats(cheats + Cheat(UUID.randomUUID().toString(), n, address, t, value.trim()))
        status = AppText.get(R.string.memory_cheat_added, n)
    }

    fun updateCheat(c: Cheat) = saveCheats(cheats.map { if (it.id == c.id) c else it })

    fun removeCheat(c: Cheat) = saveCheats(cheats.filter { it.id != c.id })

    fun setFrozen(c: Cheat, on: Boolean) {
        if (on && c.type.parse(c.value) == null) {
            status = AppText.get(R.string.memory_bad_value, c.type.label)
            return
        }
        updateCheat(c.copy(frozen = on))
    }

    private fun stopFreezing() {
        if (cheats.any { it.frozen }) cheats = cheats.map { it.copy(frozen = false) }
        freezeTargets = emptyList()
        freezeJob?.cancel()
        freezeJob = null
    }

    /** Starts the freeze loop when a cheat is frozen, and stops it when none is. */
    private fun updateFreezing() {
        freezeTargets = cheats.filter { it.frozen }.mapNotNull { c -> c.type.parse(c.value)?.let { c.address to it } }
        if (freezeTargets.isEmpty()) {
            freezeJob?.cancel()
            freezeJob = null
            return
        }
        if (freezeJob?.isActive == true) return
        val h = host
        val p = portOrNull() ?: return
        val pid = gamePid
        if (h.isEmpty() || pid == 0) return
        freezeJob = scope.launch {
            val stopped = withContext(Dispatchers.IO) { freezeLoop(h, p, pid) }
            if (stopped != null) {
                status = stopped
                if (cheats.any { it.frozen }) cheats = cheats.map { it.copy(frozen = false) }
                freezeTargets = emptyList()
            }
            freezeJob = null
        }
    }

    /** Writes the frozen values again and again on one connection. Gives a message if it had to give up. */
    private suspend fun freezeLoop(h: String, p: Int, pid: Int): String? {
        var failures = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                Ps5Debug(h, p, timeoutMs = 4000).use { d ->
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        val targets = freezeTargets
                        if (targets.isEmpty()) return null
                        for ((addr, bytes) in targets) d.write(pid, addr, bytes)
                        failures = 0
                        delay(FREEZE_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++failures >= 5) return AppText.get(R.string.memory_freeze_stopped, e.message ?: e.javaClass.simpleName)
                delay(2000)
            }
        }
        return null
    }

    // --- browse ---

    fun openProcess(p: DbgProcess) = perform(work = { d -> d.maps(p.pid) }) { m ->
        maps = m
        openProc = p
        hexAt = -1L
    }

    /** Shows 256 bytes of the open process at [address]. */
    fun showHex(address: Long) {
        val p = openProc ?: return
        val at = if (address < 0) 0L else address
        perform(work = { d -> d.read(p.pid, at, 256) }) { bytes ->
            hexAt = at
            hexData = bytes
        }
    }

    /** Opens the game's memory at [address] in Browse (from a match or a cheat). */
    fun browseTo(address: Long) {
        val s = snapshot ?: return
        if (s.game.pid == 0) return
        val p = s.processes.firstOrNull { it.pid == s.game.pid } ?: DbgProcess("eboot.bin", s.game.pid)
        view = MemoryView.BROWSE
        val start = address and 0xFL.inv()
        perform(work = { d -> d.maps(p.pid) to d.read(p.pid, start, 256) }) { (m, bytes) ->
            maps = m
            openProc = p
            hexAt = start
            hexData = bytes
        }
    }

    /** The region of the open process that holds [address], if the map is loaded. */
    fun regionOf(address: Long): DbgMap? = maps.firstOrNull { address >= it.start && address < it.end }

    fun closeHex() {
        hexAt = -1L
    }

    fun closeProcess() {
        openProc = null
        hexAt = -1L
    }
}
