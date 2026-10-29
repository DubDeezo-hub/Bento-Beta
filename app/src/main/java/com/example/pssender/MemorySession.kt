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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------------------------
// What the Memory tab knows while the app runs: the connection details, the current game, the search and its
// results, the open process in Browse, the cheats with their freeze loop, the watched addresses and the
// ready-made cheat file. It lives outside the screen, so switching tabs or turning the phone keeps everything
// (and frozen values stay frozen; FreezeService keeps the app running while they are).
// ---------------------------------------------------------------------------------------------

/** A saved address: a name, where it is, what kind of value, and the value to write or keep there. */
data class Cheat(
    val id: String,
    val name: String,
    val address: Long,
    val type: ValueType,
    val value: String,
    val frozen: Boolean = false,
    /** Cheats with the same group are listed together ("" is no group). */
    val group: String = "",
    /** The bytes around the address, to find it again after a restart (hex), the bytes that count ("1"/"0"), where the value is. */
    val sigHex: String = "",
    val sigMask: String = "",
    val sigAt: Int = 0
) {
    val signature: Signature? get() = if (sigHex.isEmpty()) null else Signature.parse(sigHex, sigMask, sigAt)
}

/** An address shown at the top of Scan with its live value. */
data class Watch(val address: Long, val type: ValueType, val value: ByteArray? = null)

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
                .put("type", c.type.name).put("value", c.value).put("group", c.group)
                .put("sig", c.sigHex).put("sigMask", c.sigMask).put("sigAt", c.sigAt)
        )
    }
    return arr.toString()
}

/** Cheats from saved settings or an exported list (an array of them, or an object with a "cheats" array). */
fun parseCheats(json: String?): List<Cheat> {
    if (json.isNullOrBlank()) return emptyList()
    val root = try {
        MiniJson.parse(json.trim())
    } catch (_: Exception) {
        return emptyList()
    }
    val list = (root as? List<*>) ?: ((root as? Map<*, *>)?.get("cheats") as? List<*>) ?: return emptyList()
    return list.mapNotNull { e ->
        val o = e as? Map<*, *> ?: return@mapNotNull null
        val addr = parseAddress(o["address"]?.toString() ?: "") ?: return@mapNotNull null
        Cheat(
            id = o["id"]?.toString()?.ifBlank { null } ?: UUID.randomUUID().toString(),
            name = o["name"]?.toString().orEmpty(),
            address = addr,
            type = ValueType.of(o["type"]?.toString()),
            value = o["value"]?.toString().orEmpty(),
            group = o["group"]?.toString().orEmpty(),
            sigHex = o["sig"]?.toString().orEmpty(),
            sigMask = o["sigMask"]?.toString().orEmpty(),
            sigAt = (o["sigAt"] as? Number)?.toInt() ?: o["sigAt"]?.toString()?.toIntOrNull() ?: 0
        )
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

/** A mod of the cheat file failed its check: the bytes in memory are neither its "on" nor its "off" bytes. */
class CheatMismatch : Exception("the game's code is not what the cheat file expects")

object MemorySession {
    /** How many matches the list shows (and refreshes). */
    const val SHOWN = 200

    /** Matches and watches are re-read this often while they can all be shown. */
    const val LIVE_MS = 1500L

    /** A frozen value is written again this often. */
    private const val FREEZE_MS = 200L

    /** After losing the console (rest mode, Wi-Fi), the freeze loop tries again this many times, 2 seconds apart. */
    private const val FREEZE_RETRIES = 90

    /** How long a scan may wait for the console: a big region with few matches sends nothing for a while. */
    private const val SCAN_TIMEOUT_MS = 300_000

    /** Survivors fetched per request when a search kept on the console is brought over. */
    private const val GET_BATCH = 8192

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    // --- connection and console ---
    var port by mutableStateOf(DEBUG_PORT.toString())
        private set
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
    var skipSlow by mutableStateOf(true)
        private set
    var results by mutableStateOf<ScanResults?>(null)
        private set
    var hits by mutableStateOf<List<MemoryHit>>(emptyList())
        private set
    var progressDone by mutableStateOf(0L)
        private set
    var progressAll by mutableStateOf(0L)
        private set
    var scanning by mutableStateOf(false)
        private set

    /** A search the console keeps (an unknown starting value): how many values are left, or -1 when there is none. */
    var keptTotal by mutableStateOf(-1L)
        private set
    private var keptType = ValueType.INT
    private var keptScans = 0
    private var keptPieces: List<ScanSegment> = emptyList()

    @Volatile
    private var kept: Ps5Debug? = null

    // --- watches ---
    var watches by mutableStateOf<List<Watch>>(emptyList())
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

    // --- ready-made cheat file ---
    var cheatSource by mutableStateOf(CheatFiles.DEFAULT_SOURCE)
        private set
    var cheatChoices by mutableStateOf<List<CheatIndexEntry>?>(null)
        private set
    var cheatFile by mutableStateOf<CheatFile?>(null)
        private set
    var cheatFileEntry by mutableStateOf<CheatIndexEntry?>(null)
        private set
    var modsOn by mutableStateOf<Set<Int>>(emptySet())
        private set
    private var cheatBase = 0L
    private var masterDone = false

    @Volatile
    private var current: Ps5Debug? = null

    @Volatile
    private var cancelRequested = false

    private var freezeJob: Job? = null

    @Volatile
    private var freezeTargets: List<Pair<Long, ByteArray>> = emptyList()

    private var host = ""

    /** A live refresh is on its way; the next one waits for it rather than piling up. */
    @Volatile
    private var refreshing = false

    /** Call from the screen, so the session can save settings and cheats. */
    fun attach(ctx: Context) {
        if (app == null) {
            app = ctx.applicationContext
            port = MemoryPrefs.port(ctx)
            skipSlow = MemoryPrefs.skipSlow(ctx)
            cheatSource = MemoryPrefs.cheatSource(ctx)
        }
    }

    /** The console's address from the console card. Another console means starting over: nothing here applies to it. */
    fun setHost(h: String) {
        if (h.trim() != host) {
            val first = host.isEmpty() && snapshot == null
            host = h.trim()
            stopFreezing()
            if (!first) {
                cancel()
                forgetGame()
                snapshot = null
                status = ""
            }
        }
    }

    fun changePort(v: String) {
        port = v
        app?.let { MemoryPrefs.setPort(it, v) }
    }

    fun changeSkipSlow(on: Boolean) {
        skipSlow = on
        app?.let { MemoryPrefs.setSkipSlow(it, on) }
    }

    fun changeCheatSource(v: String) {
        val t = v.trim().ifBlank { CheatFiles.DEFAULT_SOURCE }
        cheatSource = if (t.endsWith("/")) t else "$t/"
        app?.let { MemoryPrefs.setCheatSource(it, cheatSource) }
        cheatChoices = null
    }

    /** The pid scans, cheats and Go to work on: the game in the foreground. */
    val gamePid: Int get() = snapshot?.game?.pid ?: 0
    private val titleId: String get() = snapshot?.game?.titleId ?: ""

    /** Whether a search is under way (results here, or a search the console keeps). */
    val searching: Boolean get() = results != null || keptTotal >= 0

    /** Whether the search under way is kept on the console (it started with an unknown value and is still big). */
    val keptSearch: Boolean get() = keptTotal >= 0

    /** How many matches the search has now. */
    val total: Long get() = results?.total ?: keptTotal.coerceAtLeast(0)

    /** The kind of value of the search under way (or the one picked for the next). */
    val searchType: ValueType get() = results?.type ?: if (keptTotal >= 0) keptType else type

    val scans: Int get() = results?.scans ?: keptScans

    // --- running things on the console ---

    private fun portOrNull(): Int? = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    private fun errorText(e: Exception, p: Int): String = when {
        cancelRequested -> AppText.get(R.string.memory_cancelled)
        e is TooManyResults -> AppText.get(R.string.memory_too_many, e.limit)
        e is CheatMismatch -> AppText.get(R.string.memory_cheatfile_mismatch)
        e is ConnectException || e is SocketTimeoutException || e is NoRouteToHostException ->
            AppText.get(R.string.memory_err_no_debugger, p)
        else -> failedText(e)
    }

    /**
     * Runs [work] on a connection in the background, then [done] with its result here. Only one at a time; the
     * buttons are off meanwhile. [scan] connections get a long timeout and the scan unlock. With [keep], the
     * connection that holds the console's kept search is used (and opened if there is none), and stays open.
     */
    private fun <T> perform(scan: Boolean = false, keep: Boolean = false, work: (Ps5Debug) -> T, done: (T) -> Unit) {
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
            var conn: Ps5Debug? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val reuse = if (keep) kept else null
                    val d = reuse ?: Ps5Debug(h, p)
                    conn = d
                    current = d
                    if (reuse == null && (scan || keep)) {
                        d.setTimeout(SCAN_TIMEOUT_MS)
                        d.unlockScans()
                    }
                    work(d)
                }
                if (keep) kept = conn else conn?.close()
                status = ""
                done(result)
            } catch (e: CancellationException) {
                conn?.close()
                throw e
            } catch (e: Exception) {
                conn?.close()
                if (keep) dropKept()
                status = errorText(e, p)
            } finally {
                current = null
                busy = false
                scanning = false
            }
        }
    }

    /** Opens a short connection for something quiet (live values, a peek) that must not hold up the buttons. */
    private suspend fun <T> quietly(unlock: Boolean, work: (Ps5Debug) -> T): T? {
        val h = host
        val p = portOrNull() ?: return null
        if (h.isEmpty()) return null
        return try {
            withContext(Dispatchers.IO) {
                Ps5Debug(h, p, timeoutMs = 4000).use { d ->
                    if (unlock) d.unlockScans()
                    work(d)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Stops a running scan: the connection is closed, which ends the wait at once. */
    fun cancel() {
        cancelRequested = true
        try { current?.close() } catch (_: Exception) {}
    }

    private fun dropKept() {
        val k = kept
        kept = null
        keptTotal = -1
        keptPieces = emptyList()
        if (k != null) scope.launch(Dispatchers.IO) { k.close() }
    }

    /** Everything that belongs to one run of one game. */
    private fun forgetGame() {
        stopFreezing()
        newSearch()
        openProc = null
        hexAt = -1L
        cheats = emptyList()
        watches = emptyList()
        cheatChoices = null
        cheatFile = null
        cheatFileEntry = null
        modsOn = emptySet()
        masterDone = false
    }

    // --- connect ---

    fun connect() {
        val fresh = snapshot == null
        perform(work = { d ->
            val info = d.info()
            val procs = d.processes()
            // Only ps5debug-NG knows the foreground app; with an older debugger the game is the eboot.bin process
            val game = if (info.isNg) {
                d.foregroundApp()
            } else {
                val e = procs.firstOrNull { it.name.equals("eboot.bin", ignoreCase = true) }
                DbgApp(e?.pid ?: 0, "", "", e?.name ?: "", "")
            }
            if (fresh) {
                try { d.notify(AppText.get(R.string.memory_console_hello)) } catch (_: Exception) {}
            }
            MemorySnapshot(info, game, procs)
        }) { s ->
            val before = snapshot?.game
            snapshot = s
            if (before == null || before.pid != s.game.pid || before.titleId != s.game.titleId) {
                // A different game (or a restart of it): its old search, process and cheat state no longer apply
                forgetGame()
                cheats = app?.let { CheatStore.load(it, s.game.titleId) } ?: emptyList()
            }
        }
    }

    // --- search ---

    fun newSearch() {
        results = null
        hits = emptyList()
        progressDone = 0
        progressAll = 0
        keptScans = 0
        dropKept()
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

    /** The game's regions to search: the writable ones, without the big slow ones when that is asked for and known. */
    private fun regionsToSearch(d: Ps5Debug, pid: Int, info: DbgInfo?): List<DbgMap> {
        val regions = Scanner.searchable(d.maps(pid))
        if (!skipSlow || info?.hasTurbo != true) return regions
        return try {
            Scanner.withoutSlow(regions, d.regionSpeeds(pid))
        } catch (e: DebuggerException) {
            regions
        }
    }

    fun firstScan() {
        val pid = gamePid
        if (pid == 0) {
            status = AppText.get(R.string.memory_no_game)
            return
        }
        val info = snapshot?.info
        val t = type
        val cmp = compare
        if (cmp == ScanCompare.UNKNOWN) {
            if (info?.hasTurbo != true) {
                status = AppText.get(R.string.memory_unknown_needs_ng)
                return
            }
            scanning = true
            perform(scan = true, keep = true, work = { d ->
                val pieces = Scanner.segments(regionsToSearch(d, pid, info))
                val n = d.snapshotStart(pid, t, pieces, ::progress)
                if (n < 0) throw DebuggerException(AppText.get(R.string.memory_snapshot_failed))
                Triple(pieces, n, d.residentGet(0, SHOWN, t))
            }) { (pieces, n, first) ->
                keptPieces = pieces
                keptType = t
                keptScans = 1
                keptTotal = n
                hits = first.map { MemoryHit(it.address, it.value, null) }
                compare = ScanCompare.DECREASED
                status = AppText.get(R.string.memory_snapshot_taken, n)
            }
            if (!busy) scanning = false
            return
        }
        val data = scanBytes(t) ?: return
        scanning = true
        perform(scan = true, work = { d ->
            Scanner.first(d, pid, regionsToSearch(d, pid, info), t, cmp, data, ::progress) { cancelRequested }
        }) { r ->
            results = r
            hits = r.hits(SHOWN)
            compare = ScanCompare.EXACT
            status = AppText.get(R.string.memory_found, r.total)
        }
        if (!busy) scanning = false
    }

    fun nextScan() {
        val cmp = compare
        if (keptTotal >= 0) {
            nextKept(cmp)
            return
        }
        val prev = results ?: return
        val data = scanBytes(prev.type) ?: return
        scanning = true
        perform(scan = true, work = { d -> Scanner.next(d, prev, cmp, data, ::progress) { cancelRequested } }) { r ->
            results = r
            hits = r.hits(SHOWN, prev)
            status = AppText.get(R.string.memory_found, r.total)
        }
        if (!busy) scanning = false
    }

    /** A next scan of the search the console keeps; once few enough are left they come over and the search goes on here. */
    private fun nextKept(cmp: ScanCompare) {
        val pid = gamePid
        val t = keptType
        val data = scanBytes(t) ?: return
        val pieces = keptPieces
        val scansNow = keptScans + 1
        scanning = true
        perform(scan = true, keep = true, work = { d ->
            val left = d.residentNext(pid, t, cmp.wire, data)
            if (left <= Scanner.MAX_RESULTS) {
                val all = ArrayList<DbgSurvivor>(left.toInt())
                var start = 0
                while (start < left) {
                    val part = d.residentGet(start, GET_BATCH, t)
                    if (part.isEmpty()) break
                    all.addAll(part)
                    start += part.size
                    progress(start.toLong(), left)
                }
                try { d.residentEnd() } catch (_: Exception) {}
                Triple(left, Scanner.fromSurvivors(pid, t, pieces, all, scansNow), all.take(SHOWN))
            } else {
                Triple(left, null, d.residentGet(0, SHOWN, t))
            }
        }) { (left, r, first) ->
            if (r != null) {
                dropKept()
                results = r
                hits = first.map { MemoryHit(it.address, it.value, it.previous) }
                keptScans = 0
            } else {
                keptTotal = left
                keptScans = scansNow
                hits = first.map { MemoryHit(it.address, it.value, it.previous) }
            }
            status = AppText.get(R.string.memory_found, left)
        }
        if (!busy) scanning = false
    }

    /** Re-reads the shown matches and the watches without blocking the screen; errors are ignored (the next try may work). */
    fun refreshQuietly(withHits: Boolean = true) {
        val pid = gamePid
        val shown = if (withHits && total <= SHOWN) hits else emptyList()
        val t = searchType
        val w = watches
        if (scanning || refreshing || pid == 0 || (shown.isEmpty() && w.isEmpty())) return
        refreshing = true
        scope.launch {
            try {
                val fresh = quietly(unlock = true) { d ->
                    val h = if (shown.isEmpty()) emptyList() else Scanner.refresh(d, pid, t, shown)
                    val wv = w.map { d.read(pid, it.address, it.type.size) }
                    h to wv
                }
                if (fresh != null) {
                    val (h, wv) = fresh
                    // Keep "was" at the value of the last scan
                    if (shown.isNotEmpty() && hits === shown) hits = h.mapIndexed { i, f -> MemoryHit(f.address, f.value, shown[i].previous) }
                    if (watches === w) watches = w.mapIndexed { i, x -> x.copy(value = wv[i]) }
                }
            } finally {
                refreshing = false
            }
        }
    }

    /** [len] bytes at [address] in the game, for showing a value in every type. Null if it could not be read. */
    suspend fun peek(address: Long, len: Int): ByteArray? {
        val pid = gamePid
        if (pid == 0) return null
        return quietly(unlock = false) { d -> d.read(pid, address, len) }
    }

    // --- watches ---

    fun watch(address: Long, t: ValueType) {
        if (watches.none { it.address == address && it.type == t }) watches = watches + Watch(address, t)
        refreshQuietly(withHits = false)
    }

    fun unwatch(w: Watch) {
        watches = watches.filter { it !== w }
    }

    // --- writing values ---

    /** Writes [text] as a [t] value at [address] in the game once. */
    fun writeValue(address: Long, t: ValueType, text: String, onDone: () -> Unit = {}) {
        val bytes = t.parse(text)
        if (bytes == null) {
            status = AppText.get(R.string.memory_bad_value, t.label)
            return
        }
        writeBytes(address, bytes) {
            status = AppText.get(R.string.memory_written, t.format(bytes), hexAddr(address))
            onDone()
        }
    }

    /** Writes raw [bytes] at [address] in the game (or the open process in Browse when [inOpen]). */
    fun writeBytes(address: Long, bytes: ByteArray, inOpen: Boolean = false, onDone: () -> Unit = {}) {
        val pid = if (inOpen) openProc?.pid ?: 0 else gamePid
        if (pid == 0) {
            status = AppText.get(R.string.memory_no_game)
            return
        }
        perform(work = { d ->
            d.write(pid, address, bytes)
            if (hexAt >= 0 && inOpen) d.read(pid, hexAt, 256) else null
        }) { fresh ->
            if (fresh != null) hexData = fresh
            status = AppText.get(R.string.memory_written_bytes, bytes.size, hexAddr(address))
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

    /** Adds a cheat; with [findable] the bytes around it are kept too, so "Find again" can find it after a restart. */
    fun addCheat(name: String, address: Long, t: ValueType, value: String, group: String = "", findable: Boolean = true) {
        val n = name.trim().ifBlank { AppText.get(R.string.memory_cheat_default, cheats.size + 1) }
        val c = Cheat(UUID.randomUUID().toString(), n, address, t, value.trim(), group = group.trim())
        saveCheats(cheats + c)
        status = AppText.get(R.string.memory_cheat_added, n)
        if (findable) captureSignature(c)
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

    /** Reads the bytes around a cheat's address and keeps them as its signature (quietly; a failure just leaves none). */
    fun captureSignature(c: Cheat) {
        val pid = gamePid
        if (pid == 0) return
        scope.launch {
            val sig = quietly(unlock = false) { d ->
                val window = d.read(pid, c.address - Signature.BEFORE, Signature.BEFORE + c.type.size + Signature.AFTER)
                Signature.make(c.address, c.type.size, window, d.maps(pid))
            }
            if (sig != null) {
                val now = cheats.firstOrNull { it.id == c.id } ?: return@launch
                updateCheat(now.copy(sigHex = sig.patternHex(), sigMask = sig.maskText(), sigAt = sig.valueAt))
            }
        }
    }

    /** Looks for each cheat's signature in the game's memory and moves the cheats whose bytes turn up in one place. */
    fun findAgain(only: Cheat? = null) {
        val pid = gamePid
        val info = snapshot?.info
        val todo = (if (only != null) listOf(only) else cheats).filter { it.signature != null }
        if (pid == 0 || todo.isEmpty()) {
            status = AppText.get(R.string.memory_find_none)
            return
        }
        scanning = true
        perform(scan = true, work = { d ->
            val regions = Scanner.segments(regionsToSearch(d, pid, info))
            val all = regions.sumOf { it.length.toLong() } * todo.size
            var done = 0L
            todo.associate { c ->
                val sig = c.signature!!
                val found = ArrayList<Long>()
                for (seg in regions) {
                    if (cancelRequested) break
                    val a = d.findUnique(pid, seg.base, seg.length, sig.pattern, sig.mask)
                    if (a != 0L) found.add(a)
                    done += seg.length
                    progress(done, all)
                }
                c.id to (if (found.size == 1) found[0] + sig.valueAt else null)
            }
        }) { moved ->
            var ok = 0
            var same = 0
            val list = cheats.map { c ->
                val a = moved[c.id]
                when {
                    a == null -> c
                    a == c.address -> { same++; c }
                    else -> { ok++; c.copy(address = a) }
                }
            }
            saveCheats(list)
            val missing = moved.count { it.value == null }
            status = AppText.get(R.string.memory_find_result, ok, same, missing)
        }
        if (!busy) scanning = false
    }

    /** The game's cheats as text to share: the same format "Import" reads. */
    fun exportText(): String {
        val s = snapshot?.game
        val o = JSONObject()
            .put("bento", "cheats")
            .put("game", s?.name.orEmpty())
            .put("titleId", s?.titleId.orEmpty())
            .put("version", s?.version.orEmpty())
            .put("cheats", JSONArray(cheatsToJson(cheats.map { it.copy(frozen = false) })))
        return o.toString(2)
    }

    /** Adds the cheats in [text] (an exported list); the ones already here (same address and type) are skipped. */
    fun importText(text: String) {
        val found = parseCheats(text)
        if (found.isEmpty()) {
            status = AppText.get(R.string.memory_import_none)
            return
        }
        val fresh = found.filter { n -> cheats.none { it.address == n.address && it.type == n.type } }
            .map { it.copy(id = UUID.randomUUID().toString(), frozen = false) }
        saveCheats(cheats + fresh)
        status = AppText.get(R.string.memory_imported, fresh.size, found.size - fresh.size)
    }

    // --- freezing ---

    private fun stopFreezing() {
        if (cheats.any { it.frozen }) cheats = cheats.map { it.copy(frozen = false) }
        freezeTargets = emptyList()
        freezeJob?.cancel()
        freezeJob = null
        app?.let { FreezeService.stop(it) }
    }

    /** From the notification's Stop button. */
    fun stopAllFreezes() {
        if (cheats.any { it.frozen }) saveCheats(cheats.map { it.copy(frozen = false) }) else stopFreezing()
    }

    /** How many values are frozen now (for the notification). */
    val frozenCount: Int get() = freezeTargets.size

    val gameName: String get() = snapshot?.game?.let { it.name.ifBlank { it.titleId } }.orEmpty()

    /** Starts the freeze loop when a cheat is frozen, and stops it when none is. */
    private fun updateFreezing() {
        freezeTargets = cheats.filter { it.frozen }.mapNotNull { c -> c.type.parse(c.value)?.let { c.address to it } }
        val ctx = app
        if (freezeTargets.isEmpty()) {
            freezeJob?.cancel()
            freezeJob = null
            ctx?.let { FreezeService.stop(it) }
            return
        }
        ctx?.let { FreezeService.update(it) }
        if (freezeJob?.isActive == true) return
        val h = host
        val p = portOrNull() ?: return
        val pid = gamePid
        val multi = snapshot?.info?.hasWriteMulti == true
        if (h.isEmpty() || pid == 0) return
        freezeJob = scope.launch {
            val stopped = withContext(Dispatchers.IO) { freezeLoop(h, p, pid, multi) }
            if (stopped != null) {
                status = stopped
                if (cheats.any { it.frozen }) cheats = cheats.map { it.copy(frozen = false) }
                freezeTargets = emptyList()
                app?.let { FreezeService.stop(it) }
            }
            freezeJob = null
        }
    }

    /**
     * Writes the frozen values again and again on one connection (several at once when the debugger can). When the
     * console goes away (rest mode, Wi-Fi) it keeps trying for a while and carries on once it is back. Gives a
     * message if it had to give up.
     */
    private suspend fun freezeLoop(h: String, p: Int, pid: Int, multi: Boolean): String? {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                Ps5Debug(h, p, timeoutMs = 4000).use { d ->
                    if (failures > 0) withContext(Dispatchers.Main) { status = AppText.get(R.string.memory_freeze_back) }
                    while (currentCoroutineContext().isActive) {
                        val targets = freezeTargets
                        if (targets.isEmpty()) return null
                        if (multi && targets.size > 1) d.writeMulti(pid, targets) else for ((addr, bytes) in targets) d.write(pid, addr, bytes)
                        failures = 0
                        delay(FREEZE_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                if (failures == 1) withContext(Dispatchers.Main) { status = AppText.get(R.string.memory_freeze_retrying) }
                if (failures >= FREEZE_RETRIES) return AppText.get(R.string.memory_freeze_stopped, e.message ?: e.javaClass.simpleName)
                delay(2000)
            }
        }
        return null
    }

    // --- ready-made cheat files ---

    private fun fetch(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body?.string() ?: throw IOException("empty reply from $url")
        }
    }

    /** Finds the cheat files for the current game in the cheat source; the one for its version is opened at once. */
    fun findCheatFiles() {
        val g = snapshot?.game ?: return
        if (g.titleId.isBlank()) {
            status = AppText.get(R.string.memory_cheatfile_no_id)
            return
        }
        val src = cheatSource
        if (busy) return
        busy = true
        status = AppText.get(R.string.memory_cheatfile_looking)
        scope.launch {
            var exact: CheatIndexEntry? = null
            try {
                val choices = withContext(Dispatchers.IO) { CheatFiles.forGame(CheatFiles.parseIndex(fetch(src + "json.txt")), g.titleId, g.version) }
                cheatChoices = choices
                status = if (choices.isEmpty()) AppText.get(R.string.memory_cheatfile_none, g.titleId) else ""
                exact = choices.firstOrNull { CheatFiles.sameVersion(it.version, g.version) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = failedText(e)
            } finally {
                busy = false
            }
            exact?.let { openCheatFile(it) }
        }
    }

    /** Downloads [entry] and finds where the game is loaded, so its mods can be switched. */
    fun openCheatFile(entry: CheatIndexEntry) {
        val pid = gamePid
        val src = cheatSource
        if (pid == 0) {
            status = AppText.get(R.string.memory_no_game)
            return
        }
        perform(work = { d ->
            val file = CheatFiles.parse(fetch(src + "json/" + entry.file))
            val base = CheatFiles.baseOf(d.maps(pid), file.process, d.firstMapStart(pid))
            file to base
        }) { (file, base) ->
            cheatFile = file
            cheatFileEntry = entry
            cheatBase = base
            modsOn = emptySet()
            masterDone = false
            val g = snapshot?.game
            status = if (g != null && !CheatFiles.sameVersion(file.version, g.version)) {
                AppText.get(R.string.memory_cheatfile_other_version, file.version, g.version.ifBlank { "?" })
            } else {
                AppText.get(R.string.memory_cheatfile_loaded, file.mods.size, hexAddr(base))
            }
        }
    }

    fun closeCheatFile() {
        cheatFile = null
        cheatFileEntry = null
        modsOn = emptySet()
    }

    /** Switches mod number [index] of the open cheat file on or off (a button is just applied). */
    fun setMod(index: Int, on: Boolean) {
        val file = cheatFile ?: return
        val mod = file.mods.getOrNull(index) ?: return
        val pid = gamePid
        val base = cheatBase
        if (!mod.supported || pid == 0) return
        val needMaster = on && !masterDone && file.master.isNotEmpty()
        perform(work = { d ->
            if (needMaster) for (pt in file.master) d.write(pid, base + pt.offset, pt.on)
            for (pt in mod.patches) {
                val addr = base + pt.offset
                val now = d.read(pid, addr, maxOf(pt.on.size, pt.off.size))
                val isOn = now.copyOf(pt.on.size).contentEquals(pt.on)
                val isOff = pt.off.isNotEmpty() && now.copyOf(pt.off.size).contentEquals(pt.off)
                // Only touch code that is what the file expects: anything else means another game version
                if (!mod.button && !isOn && !isOff) throw CheatMismatch()
                if (on) {
                    if (!isOn) d.write(pid, addr, pt.on)
                } else if (pt.off.isNotEmpty() && !isOff) {
                    d.write(pid, addr, pt.off)
                }
            }
        }) {
            if (needMaster) masterDone = true
            modsOn = if (on && !mod.button) modsOn + index else modsOn - index
            status = AppText.get(if (mod.button) R.string.memory_mod_applied else if (on) R.string.memory_mod_on else R.string.memory_mod_off, mod.name)
        }
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
