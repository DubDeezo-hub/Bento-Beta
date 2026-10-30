package com.example.pssender

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.concurrent.thread

// ---------------------------------------------------------------------------------------------
// The practice game for Memory > Learn: a tiny pretend game inside the app, with a pretend debugger in front of it on
// this phone (127.0.0.1). The debugger speaks ps5debug-NG's wire protocol for every command Bento uses, so the Memory
// tab works on it exactly as on a console, and every lesson can be tried without a console, a payload or a game.
//
// The game keeps what real games keep, the way they keep it:
//  - Gold and ammo: whole numbers on screen (4 bytes): the easy lessons.
//  - Health: a bar without a number, a float from 0 to 100, with max health right after it; a timer that counts down
//    in seconds as a float: the medium lessons.
//  - The health sits in a player record that the game reaches through pointers from its own data, and it moves to a
//    new place each time the game restarts; the code that takes damage lives in the game's code; the hero's name is
//    text in UTF-8 and UTF-16: the expert lessons.
// ---------------------------------------------------------------------------------------------

object PracticeGame {
    const val PID = 91
    const val TITLE_ID = "BENT00001"
    const val NAME = "Bento Practice"

    const val EBOOT = 0x00400000L
    const val DATA = 0x00404000L
    const val BSS = 0x00406000L
    private const val EXE_SIZE = 0x4000
    private const val DATA_SIZE = 0x2000
    private const val BSS_SIZE = 0x1000
    private const val HEAP_SIZE = 0x10000

    /** Where the code that takes damage is, from the eboot's start (4 bytes: "sub eax, ecx; mov [rdi], eax"). */
    const val DAMAGE_CODE = 0x200
    val DAMAGE_BYTES = byteArrayOf(0x29, 0xC8.toByte(), 0x89.toByte(), 0x07)

    /** The pointer the game starts from, in its own data: [eboot data + 0x1100] → world, world + 0x10 → player. */
    const val ROOT_POINTER = DATA + 0x1100
    const val WORLD_TO_PLAYER = 0x10L
    const val PLAYER_HEALTH = 0x34L
    const val PLAYER_MAX_HEALTH = 0x38L

    // Offsets in the heap
    private const val GOLD = 0x2000L
    private const val AMMO = 0x2010L
    private const val TIMER = 0x3000L
    private const val NAME_UTF8 = 0x4000L
    private const val NAME_UTF16 = 0x4100L
    private const val WORLD = 0x8000L
    private const val PLAYER = 0x1200L

    const val START_GOLD = 500
    const val CLIP = 30
    const val MAX_HEALTH = 100f
    const val HIT = 7.5f
    const val ROUND_SECONDS = 300f
    const val HERO = "Aerith"

    private val lock = Any()
    private val exe = ByteArray(EXE_SIZE)
    private val data = ByteArray(DATA_SIZE)
    private val bss = ByteArray(BSS_SIZE)
    private var heap = ByteArray(HEAP_SIZE)

    /** Where the heap is in this run. A restart moves it, like a real game's memory moves from run to run. */
    @Volatile
    var heapBase = 0x20000000L
        private set

    /** How many times the game was started. */
    @Volatile
    var runs = 0
        private set

    private var lastTick = System.currentTimeMillis()

    init {
        restart()
    }

    /** The regions as the debugger lists them: name, start, bytes, protection (1 read, 2 write, 4 execute). */
    private fun regions(): List<Triple<String, Long, ByteArray>> = listOf(
        Triple("eboot.bin", EBOOT, exe),
        Triple("eboot.bin", DATA, data),
        Triple("", BSS, bss),
        Triple("heap", heapBase, heap)
    )

    fun maps(): List<DbgMap> = synchronized(lock) {
        regions().map { (name, start, bytes) -> DbgMap(name, start, start + bytes.size, 0, if (start == EBOOT) 5 else 3) }
    }

    /** Starts the game again: everything back to its first value, and the heap somewhere new. */
    fun restart() = synchronized(lock) {
        runs++
        heapBase = 0x20000000L + ((runs * 3L) % 8) * 0x100000L
        exe.fill(0)
        byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 9).copyInto(exe)
        "Bento Practice v1.00".toByteArray().copyInto(exe, 0x100)
        DAMAGE_BYTES.copyInto(exe, DAMAGE_CODE)
        data.fill(0)
        bss.fill(0)
        heap = ByteArray(HEAP_SIZE)
        // Small numbers everywhere, so a search finds more than one place (the same each run)
        val noise = java.util.Random(1234L + runs)
        for (i in 0 until HEAP_SIZE step 4) putInt(heap, i, noise.nextInt(256))
        putLong(data, (ROOT_POINTER - DATA).toInt(), heapBase + WORLD)
        putLong(heap, (WORLD + WORLD_TO_PLAYER).toInt(), heapBase + PLAYER)
        // Other things that happen to hold the same numbers (a shop price, a clip size), as in real games: the first
        // search finds several places, and the next one tells them apart
        for (at in intArrayOf(0x0640, 0x2400, 0x5A10, 0x7C00)) putInt(heap, at, START_GOLD)
        for (at in intArrayOf(0x0300, 0x2014, 0x2018, 0x6100, 0x9F00)) putInt(heap, at, CLIP)
        putInt(heap, GOLD.toInt(), START_GOLD)
        putInt(heap, AMMO.toInt(), CLIP)
        putFloat(heap, (PLAYER + PLAYER_HEALTH).toInt(), MAX_HEALTH)
        putFloat(heap, (PLAYER + PLAYER_MAX_HEALTH).toInt(), MAX_HEALTH)
        putFloat(heap, TIMER.toInt(), ROUND_SECONDS)
        val utf8 = ("Hero: $HERO").toByteArray(Charsets.UTF_8) + 0
        utf8.copyInto(heap, NAME_UTF8.toInt())
        (HERO.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)).copyInto(heap, NAME_UTF16.toInt())
        lastTick = System.currentTimeMillis()
    }

    // --- what the game shows ---

    fun gold(): Int = synchronized(lock) { getInt(heap, GOLD.toInt()) }
    fun ammo(): Int = synchronized(lock) { getInt(heap, AMMO.toInt()) }
    fun health(): Float = synchronized(lock) { getFloat(heap, (PLAYER + PLAYER_HEALTH).toInt()) }
    fun maxHealth(): Float = synchronized(lock) { getFloat(heap, (PLAYER + PLAYER_MAX_HEALTH).toInt()) }
    fun timer(): Float = synchronized(lock) { getFloat(heap, TIMER.toInt()) }

    /** The hero's name as the game shows it (from its UTF-16 copy, like a game's menu text). */
    fun name(): String = synchronized(lock) {
        val at = NAME_UTF16.toInt()
        var end = at
        while (end + 1 < heap.size && (heap[end].toInt() != 0 || heap[end + 1].toInt() != 0)) end += 2
        String(heap, at, end - at, Charsets.UTF_16LE)
    }

    /** Whether the damage code was changed (a cheat turned it into something else). */
    fun damageCodeChanged(): Boolean = synchronized(lock) { !exe.copyOfRange(DAMAGE_CODE, DAMAGE_CODE + 4).contentEquals(DAMAGE_BYTES) }

    // --- what the player does ---

    fun spendGold(n: Int) = synchronized(lock) { putInt(heap, GOLD.toInt(), getInt(heap, GOLD.toInt()) - n) }
    fun earnGold(n: Int) = synchronized(lock) { putInt(heap, GOLD.toInt(), getInt(heap, GOLD.toInt()) + n) }
    fun shoot() = synchronized(lock) { putInt(heap, AMMO.toInt(), maxOf(0, getInt(heap, AMMO.toInt()) - 1)) }
    fun reload() = synchronized(lock) { putInt(heap, AMMO.toInt(), CLIP) }

    /** A hit runs the game's damage code; when a cheat has changed that code, nothing happens. */
    fun takeHit() = synchronized(lock) {
        if (damageCodeChanged()) return@synchronized
        val at = (PLAYER + PLAYER_HEALTH).toInt()
        val hp = getFloat(heap, at) - HIT
        putFloat(heap, at, if (hp <= 0f) getFloat(heap, (PLAYER + PLAYER_MAX_HEALTH).toInt()) else hp)
    }

    /** Lets the round timer run; call often (the screen does, while the practice game is shown). */
    fun tick() = synchronized(lock) {
        val now = System.currentTimeMillis()
        val secs = (now - lastTick) / 1000f
        lastTick = now
        val t = getFloat(heap, TIMER.toInt()) - secs
        putFloat(heap, TIMER.toInt(), if (t <= 0f) ROUND_SECONDS else t)
    }

    // --- memory, for the pretend debugger ---

    /** [length] bytes at [address], or null when that range isn't mapped as one region. */
    fun read(address: Long, length: Int): ByteArray? = synchronized(lock) {
        val r = regions().firstOrNull { (_, start, b) -> address >= start && address + length <= start + b.size } ?: return null
        val off = (address - r.second).toInt()
        r.third.copyOfRange(off, off + length)
    }

    fun write(address: Long, bytes: ByteArray): Boolean = synchronized(lock) {
        val r = regions().firstOrNull { (_, start, b) -> address >= start && address + bytes.size <= start + b.size } ?: return false
        bytes.copyInto(r.third, (address - r.second).toInt())
        true
    }

    // --- little-endian helpers ---

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun getInt(b: ByteArray, at: Int) = le(b).getInt(at)
    private fun putInt(b: ByteArray, at: Int, v: Int) { le(b).putInt(at, v) }
    private fun getFloat(b: ByteArray, at: Int) = le(b).getFloat(at)
    private fun putFloat(b: ByteArray, at: Int, v: Float) { le(b).putFloat(at, v) }
    private fun putLong(b: ByteArray, at: Int, v: Long) { le(b).putLong(at, v) }

    /** Where the health is in this run (what a pointer path leads to). */
    val healthAddress: Long get() = heapBase + PLAYER + PLAYER_HEALTH
    val goldAddress: Long get() = heapBase + GOLD
}

/**
 * The pretend debugger in front of [PracticeGame], on 127.0.0.1 at a free port. It answers like ps5debug-NG 1.3 (status
 * words bit-swapped as the real server sends them), including the scan unlock and the turbo snapshot search.
 */
object PracticeConsole {
    private const val MAGIC = 0xFFAABBCC.toInt()
    private const val OK = 0x80000000.toInt()
    private const val ERROR = 0xF0000001.toInt()
    private const val REFUSED = 0xF0000003.toInt()
    private val SENTINEL = ByteArray(8) { -1 }

    @Volatile
    private var server: ServerSocket? = null

    /** The port it listens on, starting it first if needed. */
    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "practice-debugger", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(name = "practice-client", isDaemon = true) { serve(c) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private val KEYSTREAM: ByteArray by lazy {
        var s1 = 200L
        var s2 = 300L
        var s3 = 400L
        var s4 = 500L
        val m = 0xFFFFFFFFL
        ByteArray(256) {
            s1 = ((s1 shl 18) and 0xFFF80000L) xor (((s1 xor (s1 shl 6)) and m) ushr 13)
            s2 = ((s2 shl 2) and 0xFFFFFFE0L) xor (((s2 xor (s2 shl 2)) and m) ushr 27)
            s3 = ((s3 shl 7) and 0xFFFFF800L) xor (((s3 xor (s3 shl 13)) and m) ushr 21)
            s4 = ((s4 shl 13) and 0xFFF00000L) xor (((s4 xor (s4 shl 3)) and m) ushr 12)
            ((s1 xor s2 xor s3 xor s4) and 0xFF).toByte()
        }
    }

    // --- one client ---

    private class Client(val input: DataInputStream, val out: OutputStream) {
        var unlocked = false
        /** A kept search: value size and (address, now, before). */
        var session: Pair<Int, List<Triple<Long, ByteArray, ByteArray>>>? = null

        /** Reads [n] bytes; what was written is sent first, since the phone may be waiting for it before it sends more. */
        fun exact(n: Int): ByteArray {
            out.flush()
            return ByteArray(n).also { input.readFully(it) }
        }
        fun u32(v: Int) = out.write(le(4).putInt(v).array())
        fun send(b: ByteArray) = out.write(b)
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun body(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun fixed(text: String, size: Int): ByteArray = text.toByteArray().copyOf(size)

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.tcpNoDelay = true
            val c = Client(DataInputStream(s.getInputStream().buffered()), s.getOutputStream().buffered())
            try {
                while (true) {
                    val head = body(c.exact(12))
                    val magic = head.getInt(0)
                    val cmd = head.getInt(4)
                    val len = head.getInt(8)
                    val b = if (len > 0) c.exact(len) else ByteArray(0)
                    if (magic != MAGIC) continue
                    handle(c, cmd, b)
                    c.out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(c: Client, cmd: Int, b: ByteArray) {
        val g = PracticeGame
        val bb = body(b)
        when (cmd) {
            0xBD000001.toInt() -> { c.u32(3); c.send("1.3".toByteArray()) } // version
            0xBD000500.toInt() -> c.send(le(2).putShort(1040.toShort()).array()) // firmware
            0xBD000501.toInt() -> { // branding
                val text = "ps5debug-NG by OSR v1.3.2 [Bento practice]".toByteArray() + 0 + "1.1".toByteArray()
                c.u32(text.size); c.send(text)
            }
            0xBDAACC06.toInt(), 0xBDDD0005.toInt() -> c.u32(OK) // nop, info
            0xBDAA0001.toInt() -> { // process list
                val procs = listOf("kernel" to 0, "init" to 1, "SceSysCore.elf" to 30, "SceShellCore" to 47, "SceShellUI" to 48, "eboot.bin" to PracticeGame.PID)
                c.u32(OK); c.u32(procs.size)
                procs.forEach { (n, p) -> c.send(fixed(n, 32) + le(4).putInt(p).array()) }
            }
            0xBDDD0006.toInt() -> { // foreground app
                c.u32(OK)
                c.send(le(4).putInt(PracticeGame.PID).array() + fixed(PracticeGame.TITLE_ID, 16) +
                    fixed("UP0000-${PracticeGame.TITLE_ID}_00-BENTOPRACTICE000", 64) + fixed(PracticeGame.NAME, 40) + fixed("01.000", 16))
            }
            0xBDAA0004.toInt() -> { // memory maps
                val maps = if (bb.getInt(0) == PracticeGame.PID) g.maps() else emptyList()
                c.u32(OK); c.u32(maps.size)
                maps.forEach { m -> c.send(fixed(m.name, 32) + le(26).putLong(m.start).putLong(m.end).putLong(0).putShort(m.prot.toShort()).array()) }
            }
            0xBDAA0002.toInt() -> { // read: always success, zeros where nothing is mapped
                val pid = bb.getInt(0); val addr = bb.getLong(4); val n = bb.getInt(12)
                c.u32(OK); c.send((if (pid == PracticeGame.PID) g.read(addr, n) else null) ?: ByteArray(n))
            }
            0xBDAA0003.toInt() -> { // write: ack, the bytes, the result
                val pid = bb.getInt(0); val addr = bb.getLong(4); val n = bb.getInt(12)
                c.u32(OK)
                val data = c.exact(n)
                c.u32(if (pid == PracticeGame.PID && g.write(addr, data)) OK else ERROR)
            }
            0xBDDD0004.toInt() -> { c.exact(bb.getInt(4)); c.u32(OK) } // notify
            0xBDAACCFF.toInt() -> { // scan unlock
                if (bb.getInt(0) != 0xBB40E64D.toInt()) { c.u32(REFUSED); return }
                c.u32(OK)
                val challenge = ByteArray(64).also { SecureRandom().nextBytes(it) }
                c.send(le(2).putShort(64).array() + challenge)
                val answer = c.exact(64)
                val good = answer.indices.all { answer[it] == (challenge[it].toInt() xor KEYSTREAM[it].toInt()).toByte() }
                if (good && bb.getInt(4) and 2 != 0) c.unlocked = true
                c.u32(if (good) OK else REFUSED)
            }
            0xBDAA000D.toInt() -> { c.u32(OK); c.send(le(8).putLong(if (bb.getInt(0) == PracticeGame.PID) PracticeGame.EBOOT else 0).array()) } // first map
            0xBDAACC04.toInt() -> { // write many
                val pid = bb.getInt(0); val count = bb.getInt(4)
                c.u32(OK)
                var ok = true
                repeat(count) {
                    val e = body(c.exact(12))
                    val data = c.exact(e.getInt(8))
                    ok = (pid == PracticeGame.PID && g.write(e.getLong(0), data)) && ok
                }
                c.u32(if (ok) OK else ERROR)
            }
            0xBDAA0501.toInt() -> { // pattern search: the address if it occurs exactly once
                val pid = bb.getInt(0); val addr = bb.getLong(4); val n = bb.getInt(12); val plen = bb.getInt(18)
                c.u32(OK)
                val pattern = c.exact(plen); val mask = c.exact(plen)
                c.u32(OK)
                val mem = (if (pid == PracticeGame.PID) g.read(addr, n) else null) ?: ByteArray(n)
                val found = (0..n - plen).filter { j -> (0 until plen).all { i -> mask[i].toInt() != 1 || mem[j + i] == pattern[i] } }
                c.send(le(8).putLong(if (found.size == 1) addr + found[0] else 0).array())
                c.u32(OK)
            }
            0xBDAACC10.toInt() -> { c.u32(OK); c.send(le(16).putInt(1).putInt(0x04 or 0x08 or 0x10).putInt(1).putInt(0).array()) } // turbo caps
            0xBDAACC16.toInt() -> { // region speeds
                if (!c.unlocked) { c.u32(REFUSED); return }
                val maps = if (bb.getInt(0) == PracticeGame.PID) g.maps() else emptyList()
                c.u32(OK); c.u32(maps.size)
                maps.forEach { m -> c.send(le(32).putLong(m.start).putLong(m.end).putInt(m.prot).putInt(0).putInt(2000).putInt(0).array()) }
                c.u32(OK)
            }
            0xBDAACC11.toInt() -> turboStart(c, bb)
            0xBDAACC12.toInt() -> turboCount(c, bb)
            0xBDAACC13.toInt() -> { // survivors of the kept search
                val start = bb.getInt(0); val count = bb.getInt(4)
                c.u32(OK)
                val sess = c.session
                if (sess == null) c.u32(0) else {
                    val part = sess.second.drop(start).take(count)
                    c.u32(part.size)
                    part.forEach { (a, now, before) -> c.send(le(8).putLong(a).array() + now + before) }
                }
                c.u32(OK)
            }
            0xBDAACC14.toInt() -> { c.session = null; c.u32(OK) }
            0xBDAACC01.toInt() -> scanStart(c, bb)
            0xBDAACC02.toInt() -> scanCount(c, bb)
            0xBDAACC03.toInt() -> { // read many
                if (!c.unlocked) { c.u32(REFUSED); return }
                val pid = bb.getInt(0); val count = bb.getInt(4)
                c.u32(OK)
                val entries = body(c.exact(12 * count))
                for (i in 0 until count) {
                    val n = entries.getInt(12 * i + 8)
                    c.send((if (pid == PracticeGame.PID) g.read(entries.getLong(12 * i), n) else null) ?: ByteArray(n))
                }
                c.send(SENTINEL)
            }
            else -> c.u32(ERROR)
        }
    }

    // --- searching, as ps5debug-NG's scan.c and scan_compare.c do it ---

    private val SIZES = intArrayOf(1, 1, 2, 2, 4, 4, 8, 8, 4, 8)
    private val NEEDS_VALUE = intArrayOf(1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 0, 0, 1)
    private val NEEDS_EXTRA = intArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
    private val NEEDS_PREVIOUS = intArrayOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0)

    /** A value of type [t] as a number (whole types as Long, the two float types as Double). */
    private fun num(t: Int, b: ByteArray): Number {
        val v = body(b)
        return when (t) {
            0 -> (b[0].toLong() and 0xFF); 1 -> b[0].toLong()
            2 -> (v.getShort(0).toLong() and 0xFFFF); 3 -> v.getShort(0).toLong()
            4 -> (v.getInt(0).toLong() and 0xFFFFFFFFL); 5 -> v.getInt(0).toLong()
            6, 7 -> v.getLong(0)
            8 -> v.getFloat(0).toDouble()
            else -> v.getDouble(0)
        }
    }

    private fun less(t: Int, a: Number, b: Number): Int = when (t) {
        6 -> java.lang.Long.compareUnsigned(a.toLong(), b.toLong())
        8, 9 -> a.toDouble().compareTo(b.toDouble())
        else -> a.toLong().compareTo(b.toLong())
    }

    /** [p] plus or minus [d], wrapped the way C wraps a value of type [t]. */
    private fun shifted(t: Int, p: Number, d: Number, plus: Boolean): Number {
        if (t >= 8) return if (plus) p.toDouble() + d.toDouble() else p.toDouble() - d.toDouble()
        val bits = SIZES[t] * 8
        var r = if (plus) p.toLong() + d.toLong() else p.toLong() - d.toLong()
        if (bits < 64) {
            r = r and ((1L shl bits) - 1)
            if (t % 2 == 1 && r >= (1L shl (bits - 1))) r -= (1L shl bits)
        }
        return r
    }

    private fun matches(cmp: Int, t: Int, scan: ByteArray, mem: ByteArray, prev: ByteArray?): Boolean {
        val m = num(t, mem)
        if (cmp == 0) {
            if (t == 8 || t == 9) {
                if (scan.all { it.toInt() == 0 }) return mem.all { it.toInt() == 0 }
                val sv = num(t, scan).toDouble()
                return Math.abs(sv - m.toDouble()) <= Math.abs(sv) * 1e-6
            }
            return scan.contentEquals(mem)
        }
        if (cmp == 2 || cmp == 3) {
            val c = less(t, m, num(t, scan))
            return if (cmp == 2) c > 0 else c < 0
        }
        if (prev == null) return false
        if (cmp == 4) {
            val lo = num(t, scan)
            val hi = num(t, prev)
            return if (less(t, hi, lo) > 0) less(t, m, lo) >= 0 && less(t, m, hi) <= 0 else less(t, m, hi) >= 0 && less(t, m, lo) <= 0
        }
        val p = num(t, prev)
        return when (cmp) {
            5 -> less(t, m, p) > 0
            7 -> less(t, m, p) < 0
            6, 8 -> less(t, m, shifted(t, p, num(t, scan), cmp == 6)) == 0
            9 -> !mem.contentEquals(prev)
            10 -> mem.contentEquals(prev)
            else -> false
        }
    }

    /** Result blocks: u64 length and records, at most 0x3FFE8 bytes each, then the sentinel. */
    private fun sendBlocks(c: Client, records: List<ByteArray>) {
        val block = java.io.ByteArrayOutputStream()
        for (r in records) {
            if (block.size() + r.size > 0x3FFE8) {
                c.send(le(8).putLong(block.size().toLong()).array() + block.toByteArray())
                block.reset()
            }
            block.write(r)
        }
        if (block.size() > 0) c.send(le(8).putLong(block.size().toLong()).array() + block.toByteArray())
        c.send(SENTINEL)
    }

    private fun mem(pid: Int, a: Long, n: Int): ByteArray = (if (pid == PracticeGame.PID) PracticeGame.read(a, n) else null) ?: ByteArray(n)

    private fun scanStart(c: Client, bb: ByteBuffer) {
        val pid = bb.getInt(0); val address = bb.getLong(4); val length = bb.getInt(12)
        val t = bb.get(16).toInt(); val cmp = bb.get(17).toInt(); val align = bb.get(18).toInt(); val lenData = bb.getInt(19)
        if (t == DbgProtocol.TYPE_BYTES) {
            if (!c.unlocked || cmp != 0 || lenData == 0) { c.u32(REFUSED); return }
            c.u32(OK)
            val pattern = c.exact(lenData); val mask = c.exact(lenData)
            c.u32(OK)
            val m = mem(pid, address, length)
            val step = if (align > 0) align else lenData
            val out = ArrayList<ByteArray>()
            var j = 0
            while (j <= length - lenData) {
                if ((0 until lenData).all { k -> mask[k].toInt() != 1 || m[j + k] == pattern[k] }) out.add(le(4).putInt(j).array() + m.copyOfRange(j, j + lenData))
                j += step
            }
            sendBlocks(c, out)
            c.u32(OK)
            return
        }
        if (!c.unlocked || cmp > 12 || lenData == 0 || t !in 0..9) { c.u32(REFUSED); return }
        val size = SIZES[t]
        val needs = ((1 shl cmp) and 0x114F) != 0 || cmp == 4
        c.u32(OK)
        val pattern = if (needs) c.exact(lenData) else ByteArray(0)
        c.u32(OK)
        val m = mem(pid, address, length)
        val scan = pattern.copyOf(size)
        val hi = if (cmp == 4) pattern.copyOfRange(size, 2 * size) else null
        val step = if (align > 0) align else size
        val out = ArrayList<ByteArray>()
        var j = 0
        while (j <= length - size) {
            val v = m.copyOfRange(j, j + size)
            if (matches(cmp, t, scan, v, hi)) out.add(le(4).putInt(j).array() + v)
            j += step
        }
        sendBlocks(c, out)
        c.u32(OK)
    }

    private fun scanCount(c: Client, bb: ByteBuffer) {
        val pid = bb.getInt(0); val base = bb.getLong(4); val t = bb.get(12).toInt(); val cmp = bb.get(13).toInt(); val lenData = bb.getInt(14)
        if (!c.unlocked || cmp > 12 || t !in 0..9) { c.u32(REFUSED); return }
        val size = SIZES[t]
        c.u32(OK)
        val needs = NEEDS_VALUE[cmp] == 1 || NEEDS_EXTRA[cmp] == 1
        val pattern = if (needs && lenData > 0) c.exact(lenData) else ByteArray(0)
        val withPrev = NEEDS_PREVIOUS[cmp] == 1
        val entry = if (withPrev) 4 + size else 4
        val scan = pattern.copyOf(size)
        val hi = if (cmp == 4 && pattern.size >= 2 * size) pattern.copyOfRange(size, 2 * size) else null
        while (true) {
            val chunkLen = body(c.exact(4)).getInt(0)
            if (chunkLen == -1) break
            val chunk = c.exact(chunkLen)
            val cb = body(chunk)
            val out = ArrayList<ByteArray>()
            var off = 0
            while (off <= chunkLen - entry) {
                val o = cb.getInt(off)
                val prev = if (withPrev) chunk.copyOfRange(off + 4, off + 4 + size) else hi
                val v = mem(pid, base + (o.toLong() and 0xFFFFFFFFL), size)
                if (matches(cmp, t, scan, v, prev)) out.add(le(4).putInt(o).array() + v)
                off += entry
            }
            sendBlocks(c, out)
        }
        c.u32(OK)
    }

    private fun turboStart(c: Client, bb: ByteBuffer) {
        val pid = bb.getInt(0); val address = bb.getLong(4); val length = bb.getInt(12)
        val t = bb.get(16).toInt(); val align = bb.get(18).toInt(); val flags = bb.getInt(23)
        if (!c.unlocked || flags and 0x04 == 0 || t !in 0..9) { c.u32(REFUSED); return }
        val size = SIZES[t]
        c.u32(OK); c.u32(OK)
        val segs = ArrayList<Pair<Long, Int>>()
        if (flags and 0x10 != 0) {
            val n = body(c.exact(4)).getInt(0)
            repeat(n) { val e = body(c.exact(12)); segs.add(e.getLong(0) to e.getInt(8)) }
        } else segs.add(address to length)
        val total = segs.sumOf { it.second.toLong() }
        c.send(le(16).putLong(total / size).putLong(total).array())
        val items = ArrayList<Triple<Long, ByteArray, ByteArray>>()
        var done = 0L
        for ((a, l) in segs) {
            val m = mem(pid, a, l)
            var j = 0
            val step = if (align > 0) align else size
            while (j <= l - size) {
                val v = m.copyOfRange(j, j + size)
                if (v.any { it.toInt() != 0 }) items.add(Triple(a + j, v, v))
                j += step
            }
            done += l
            c.send(le(8).putLong(done).array())
        }
        c.send(SENTINEL)
        c.session = size to items
        c.send(le(12).putInt(1).putLong(items.size.toLong()).array())
        c.u32(OK)
    }

    private fun turboCount(c: Client, bb: ByteBuffer) {
        val pid = bb.getInt(0); val t = bb.get(12).toInt(); val cmp = bb.get(13).toInt(); val lenData = bb.getInt(14)
        if (!c.unlocked || t !in 0..9 || cmp > 12) { c.u32(REFUSED); return }
        c.u32(OK)
        val needs = NEEDS_VALUE[cmp] == 1 || NEEDS_EXTRA[cmp] == 1
        val pattern = if (needs && lenData > 0) c.exact(lenData) else ByteArray(0)
        val sess = c.session
        if (sess == null) {
            c.send(SENTINEL + le(8).putLong(0).array())
            c.u32(OK)
            return
        }
        val size = sess.first
        val scan = pattern.copyOf(size)
        val hi = if (cmp == 4) pattern.copyOfRange(size, minOf(pattern.size, 2 * size)).copyOf(size) else null
        val kept = sess.second.mapNotNull { (a, cur, _) ->
            val now = mem(pid, a, size)
            if (matches(cmp, t, scan, now, if (cmp == 4) hi else cur)) Triple(a, now, cur) else null
        }
        c.session = size to kept
        c.send(SENTINEL + le(8).putLong(kept.size.toLong()).array())
        c.u32(OK)
    }
}
