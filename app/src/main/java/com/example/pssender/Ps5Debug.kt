package com.example.pssender // change to match your project's package name

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ---------------------------------------------------------------------------------------------
// A client for ps5debug-NG, the debugger payload for jailbroken PS5s (github.com/Pharaoh2k/ps5debug-NG).
// Once its .elf is loaded on the console it listens on TCP port 744 and can list processes, read and write their
// memory, list their memory maps and scan for values. This file only speaks the wire protocol; it has no Android
// parts, so it can be tested on its own (fake_console.py in the repository answers on port 744 like the real one).
//
// The protocol in short (PROTOCOL.md in the ps5debug-NG repository has every detail):
//   * every request starts with a 12-byte header: u32 magic 0xFFAABBCC, u32 command, u32 length of the body
//   * all numbers are little-endian
//   * most replies start with a u32 status word; on a PS5 the server swaps its bit pairs before sending, so the
//     values that arrive are the swapped ones (success arrives as 0x80000000, not 0x40000000)
// ---------------------------------------------------------------------------------------------

/** The usual port of ps5debug-NG's command server. */
const val DEBUG_PORT = 744

/** One process on the console, from the process list. */
data class DbgProcess(val name: String, val pid: Int)

/** The game in the foreground. [pid] is 0 when no game is running. */
data class DbgApp(val pid: Int, val titleId: String, val contentId: String, val name: String, val version: String)

/** One mapped region of a process's memory. [prot] is the usual 1 = read, 2 = write, 4 = execute bits. */
data class DbgMap(val name: String, val start: Long, val end: Long, val offset: Long, val prot: Int) {
    val size: Long get() = end - start
    val readable: Boolean get() = prot and 1 != 0
    val writable: Boolean get() = prot and 2 != 0

    /** "rw-", "r-x" and so on. */
    val protText: String
        get() = (if (prot and 1 != 0) "r" else "-") + (if (prot and 2 != 0) "w" else "-") + (if (prot and 4 != 0) "x" else "-")
}

/** What the debugger says about itself: its name and version, the protocol version and the console's firmware. */
data class DbgInfo(val brand: String, val protocol: String, val firmware: String, val capability: String = "") {
    /** Whether this is ps5debug-NG (not the older ps5debug), which has the foreground-app command. */
    val isNg: Boolean get() = brand.contains("ps5debug-NG", ignoreCase = true)

    /** The version in the brand ("v1.3.2" gives 1.3), or 0.0 when there is none. */
    val version: Double
        get() = Regex("""v(\d+)\.(\d+)""").find(brand)?.let { m ->
            (m.groupValues[1] + "." + m.groupValues[2].padStart(2, '0')).toDoubleOrNull()
        } ?: 0.0

    /** Turbo scans (unknown starting value, region speeds) came with ps5debug-NG 1.3. */
    val hasTurbo: Boolean get() = isNg && version >= 1.03

    /** Several writes in one request came with capability level 1.0. */
    val hasWriteMulti: Boolean get() = isNg && (capability.toDoubleOrNull() ?: 0.0) >= 1.0
}

/** How fast a region reads, from ps5debug-NG's region check. [uncached] memory (the GPU's) is the slow kind. */
data class DbgRegionSpeed(val start: Long, val end: Long, val prot: Int, val uncached: Boolean, val mbps: Int)

/** One survivor of a server-side search: where it is, its value now and at the scan before. */
class DbgSurvivor(val address: Long, val value: ByteArray, val previous: ByteArray)

/** The debugger answered, but with an error (or something this app could not understand). */
class DebuggerException(message: String) : IOException(message)

object DbgProtocol {
    val MAGIC = 0xFFAABBCC.toInt()

    val CMD_VERSION = 0xBD000001.toInt()
    val CMD_FW_VERSION = 0xBD000500.toInt()
    val CMD_BRANDING = 0xBD000501.toInt()
    val CMD_NOP = 0xBDAACC06.toInt()
    val CMD_PROC_LIST = 0xBDAA0001.toInt()
    val CMD_PROC_READ = 0xBDAA0002.toInt()
    val CMD_PROC_WRITE = 0xBDAA0003.toInt()
    val CMD_PROC_MAPS = 0xBDAA0004.toInt()
    val CMD_NOTIFY = 0xBDDD0004.toInt()
    val CMD_FOREGROUND_APP = 0xBDDD0006.toInt()
    val CMD_PROC_AUTH = 0xBDAACCFF.toInt()
    val CMD_SCAN_START = 0xBDAACC01.toInt()
    val CMD_SCAN_COUNT = 0xBDAACC02.toInt()
    val CMD_SCAN_GET = 0xBDAACC03.toInt()
    val CMD_WRITE_MULTI = 0xBDAACC04.toInt()
    val CMD_FIRST_MAP = 0xBDAA000D.toInt()
    val CMD_SCAN_AOB = 0xBDAA0501.toInt()
    val CMD_TURBO_CAPS = 0xBDAACC10.toInt()
    val CMD_TURBO_START = 0xBDAACC11.toInt()
    val CMD_TURBO_COUNT = 0xBDAACC12.toInt()
    val CMD_TURBO_GET = 0xBDAACC13.toInt()
    val CMD_TURBO_END = 0xBDAACC14.toInt()
    val CMD_TURBO_REGIONS = 0xBDAACC16.toInt()

    // Turbo scan flags (protocol.h)
    const val TS_SERVER_RESIDENT = 0x02
    const val TS_SNAPSHOT = 0x04
    const val TS_SNAPSHOT_SEGMENTS = 0x10

    // Turbo engine bits from the capability probe
    const val TSE_SERVER_RESIDENT = 0x04
    const val TSE_SNAPSHOT = 0x08
    const val TSE_SNAPSHOT_SEGMENTS = 0x10

    /** Compare type of a first scan that remembers every value (the "unknown starting value" search). */
    const val CMP_UNKNOWN = 11

    /** The magic of the scan unlock (CMD_PROC_AUTH), sent as is. */
    val AUTH_MAGIC = 0xBB40E64D.toInt()

    /** A u64 of all ones ends every streamed list. */
    const val SENTINEL = -1L

    /** The most bytes of candidates the server takes in one piece of a next scan. */
    const val COUNT_CHUNK = 0x40000

    // Which compare types the server reads a value for, per compare type 0..12 (scan_compare.c)
    private val NEEDS_VALUE = booleanArrayOf(true, true, true, true, true, false, true, false, true, false, false, false, true)
    private val NEEDS_EXTRA = booleanArrayOf(false, false, false, false, true, false, false, false, false, false, false, false, false)
    private val NEEDS_PREVIOUS = booleanArrayOf(false, false, false, false, false, true, true, true, true, true, true, false, false)

    /** Whether a first scan (SCAN_START) reads a value for [cmp]. It must match the server, or the stream gets out of step. */
    fun startNeedsValue(cmp: Int): Boolean = ((1 shl cmp) and 0x114F) != 0 || cmp == 4

    /** Whether a next scan (SCAN_COUNT) reads a value for [cmp]. */
    fun countNeedsValue(cmp: Int): Boolean = NEEDS_VALUE[cmp] || NEEDS_EXTRA[cmp]

    /** Whether a next scan wants each candidate's previous value after its offset. */
    fun countNeedsPrevious(cmp: Int): Boolean = NEEDS_PREVIOUS[cmp]

    /** The 256-byte keystream of the scan unlock: xorshift seeded with 200, 300, 400, 500 (auth.c). */
    fun authKeystream(): ByteArray {
        var s1 = 200
        var s2 = 300
        var s3 = 400
        var s4 = 500
        return ByteArray(256) {
            s1 = ((s1 shl 18) and 0xFFF80000.toInt()) xor ((s1 xor (s1 shl 6)) ushr 13)
            s2 = ((s2 shl 2) and 0xFFFFFFE0.toInt()) xor ((s2 xor (s2 shl 2)) ushr 27)
            s3 = ((s3 shl 7) and 0xFFFFF800.toInt()) xor ((s3 xor (s3 shl 13)) ushr 21)
            s4 = ((s4 shl 13) and 0xFFF00000.toInt()) xor ((s4 xor (s4 shl 3)) ushr 12)
            (s1 xor s2 xor s3 xor s4).toByte()
        }
    }

    /** The answer to the server's challenge: each byte XOR the keystream. */
    fun authResponse(challenge: ByteArray): ByteArray {
        val k = authKeystream()
        return ByteArray(challenge.size) { (challenge[it].toInt() xor k[it and 0xFF].toInt()).toByte() }
    }

    // Status words as they arrive (already bit-swapped by the server)
    val STATUS_SUCCESS = 0x80000000.toInt()
    val STATUS_ERROR = 0xF0000001.toInt()
    val STATUS_DATA_NULL = 0xF0000003.toInt()
    val STATUS_ALREADY_DEBUG = 0xF0000004.toInt()
    val STATUS_INVALID_INDEX = 0xF0000005.toInt()

    /** The server refuses a request body larger than this, and reads and writes are kept to it too. */
    const val MAX_CHUNK = 0x100000

    /** Swaps each pair of neighbouring bits; doing it twice gives the original back. */
    fun bitswap(x: Int): Int = ((x ushr 1) and 0x55555555) or ((x shl 1) and 0xAAAAAAAA.toInt())

    fun statusName(s: Int): String = when (s) {
        STATUS_SUCCESS -> "success"
        STATUS_ERROR -> "error"
        STATUS_DATA_NULL -> "no data"
        STATUS_ALREADY_DEBUG -> "already being debugged"
        STATUS_INVALID_INDEX -> "invalid index"
        else -> "unexpected reply 0x%08X".format(s)
    }

    /** "10.40" from the debugger's 1040. */
    fun firmwareText(n: Int): String = if (n <= 0) "?" else "%d.%02d".format(n / 100, n % 100)

    /** The text in a fixed-size field, up to its first zero byte. */
    fun cString(b: ByteArray, from: Int, len: Int): String {
        var end = from
        val stop = minOf(b.size, from + len)
        while (end < stop && b[end] != 0.toByte()) end++
        return String(b, from, end - from, Charsets.UTF_8).trim()
    }

    fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

/**
 * One connection to ps5debug-NG. Every call blocks, so use it from a background thread (Dispatchers.IO), and only from
 * one thread at a time. Close it when done; [withDebugger] does that for you.
 */
class Ps5Debug(host: String, port: Int = DEBUG_PORT, timeoutMs: Int = 8000) : Closeable {
    private val sock = Socket()
    private val input: DataInputStream
    private val output: OutputStream

    init {
        try {
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.soTimeout = timeoutMs
            sock.tcpNoDelay = true
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            throw e
        }
        input = DataInputStream(sock.getInputStream().buffered())
        output = sock.getOutputStream().buffered()
    }

    override fun close() {
        try { sock.close() } catch (_: Exception) {}
    }

    // --- low-level helpers ----------------------------------------------------------------------

    private fun send(cmd: Int, body: ByteArray = ByteArray(0), datalen: Int = body.size, trailing: ByteArray? = null) {
        val head = DbgProtocol.le(12).putInt(DbgProtocol.MAGIC).putInt(cmd).putInt(datalen).array()
        output.write(head)
        output.write(body)
        if (trailing != null) output.write(trailing)
        output.flush()
    }

    private fun bytes(n: Int): ByteArray {
        val b = ByteArray(n)
        input.readFully(b)
        return b
    }

    private fun wrap(n: Int): ByteBuffer = ByteBuffer.wrap(bytes(n)).order(ByteOrder.LITTLE_ENDIAN)
    private fun u16(): Int = wrap(2).short.toInt() and 0xFFFF
    private fun u32(): Int = wrap(4).int

    private fun expectOk(what: String) {
        val s = u32()
        if (s != DbgProtocol.STATUS_SUCCESS) throw DebuggerException("$what: ${DbgProtocol.statusName(s)}")
    }

    /** A count from the server, with a sanity limit so a garbled reply cannot make the app allocate gigabytes. */
    private fun count(what: String, limit: Int): Int {
        val n = u32()
        if (n < 0 || n > limit) throw DebuggerException("$what: implausible count $n")
        return n
    }

    // --- commands -------------------------------------------------------------------------------

    /** Checks that the server answers at all. */
    fun ping() {
        send(DbgProtocol.CMD_NOP)
        expectOk("ping")
    }

    /** The protocol version, e.g. "1.3". (This reply has no status word.) */
    fun protocolVersion(): String {
        send(DbgProtocol.CMD_VERSION)
        val len = count("version", 256)
        return String(bytes(len), Charsets.UTF_8).trim()
    }

    /** The firmware as the debugger reports it, e.g. 1040 for 10.40. (No status word.) */
    fun firmware(): Int {
        send(DbgProtocol.CMD_FW_VERSION)
        return u16()
    }

    /** "ps5debug-NG by OSR v1.3.2 [...]" and, after a zero byte, a capability level such as "1.1". (No status word.) */
    fun branding(): Pair<String, String> {
        send(DbgProtocol.CMD_BRANDING)
        val len = count("branding", 4096)
        val b = bytes(len)
        val brand = DbgProtocol.cString(b, 0, b.size)
        val zero = b.indexOf(0.toByte())
        val cap = if (zero >= 0 && zero + 1 < b.size) String(b, zero + 1, b.size - zero - 1, Charsets.UTF_8).trim() else ""
        return brand to cap
    }

    fun info(): DbgInfo {
        val (brand, cap) = branding()
        return DbgInfo(brand = brand, protocol = protocolVersion(), firmware = DbgProtocol.firmwareText(firmware()), capability = cap)
    }

    /** Every process on the console. */
    fun processes(): List<DbgProcess> {
        send(DbgProtocol.CMD_PROC_LIST)
        expectOk("process list")
        val n = count("process list", 100_000)
        val raw = bytes(n * 36)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until n).map { i ->
            DbgProcess(name = DbgProtocol.cString(raw, i * 36, 32), pid = buf.getInt(i * 36 + 32))
        }
    }

    /** The game in the foreground (pid 0 when there is none). */
    fun foregroundApp(): DbgApp {
        send(DbgProtocol.CMD_FOREGROUND_APP)
        expectOk("current game")
        val raw = bytes(140)
        val pid = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        return DbgApp(
            pid = pid,
            titleId = DbgProtocol.cString(raw, 4, 16),
            contentId = DbgProtocol.cString(raw, 20, 64),
            name = DbgProtocol.cString(raw, 84, 40),
            version = DbgProtocol.cString(raw, 124, 16)
        )
    }

    /** The mapped memory regions of process [pid]. */
    fun maps(pid: Int): List<DbgMap> {
        send(DbgProtocol.CMD_PROC_MAPS, DbgProtocol.le(4).putInt(pid).array())
        expectOk("memory map")
        val n = count("memory map", 1_000_000)
        val raw = bytes(n * 58)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until n).map { i ->
            val at = i * 58
            DbgMap(
                name = DbgProtocol.cString(raw, at, 32),
                start = buf.getLong(at + 32),
                end = buf.getLong(at + 40),
                offset = buf.getLong(at + 48),
                prot = buf.getShort(at + 56).toInt() and 0xFFFF
            )
        }
    }

    /** [length] bytes of process [pid]'s memory from [address]. Longer reads are split into 1 MB requests. */
    fun read(pid: Int, address: Long, length: Int): ByteArray {
        require(length >= 0) { "negative length" }
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val part = minOf(DbgProtocol.MAX_CHUNK, length - done)
            val body = DbgProtocol.le(16).putInt(pid).putLong(address + done).putInt(part).array()
            send(DbgProtocol.CMD_PROC_READ, body)
            expectOk("read at 0x%X".format(address + done))
            input.readFully(out, done, part)
            done += part
        }
        return out
    }

    /** Writes [data] into process [pid]'s memory at [address]. The server checks each write by reading it back. */
    fun write(pid: Int, address: Long, data: ByteArray) {
        var done = 0
        while (done < data.size) {
            val part = minOf(DbgProtocol.MAX_CHUNK, data.size - done)
            val body = DbgProtocol.le(16).putInt(pid).putLong(address + done).putInt(part).array()
            send(DbgProtocol.CMD_PROC_WRITE, body)
            expectOk("write at 0x%X".format(address + done))
            output.write(data, done, part)
            output.flush()
            expectOk("write at 0x%X".format(address + done))
            done += part
        }
    }

    /** Shows [text] as a notification on the console's screen. */
    fun notify(text: String) {
        val msg = (text + "\u0000").toByteArray(Charsets.UTF_8)
        val body = DbgProtocol.le(8).putInt(222).putInt(msg.size).array()
        send(DbgProtocol.CMD_NOTIFY, body, datalen = body.size, trailing = msg)
        expectOk("notification")
    }

    /** How long a read may wait for the server. Scans of big regions send nothing for a while, so they wait longer. */
    fun setTimeout(ms: Int) {
        sock.soTimeout = ms
    }

    /**
     * Unlocks the scan commands (SCAN_START, SCAN_COUNT, SCAN_GET): the server sends a 64-byte challenge and expects
     * it back XORed with a fixed keystream. The unlock is global on the console, but doing it again is harmless.
     */
    fun unlockScans() {
        val body = DbgProtocol.le(8).putInt(DbgProtocol.AUTH_MAGIC).putInt(2).array()
        send(DbgProtocol.CMD_PROC_AUTH, body)
        expectOk("scan unlock")
        val len = u16()
        if (len <= 0 || len > 4096) throw DebuggerException("scan unlock: implausible challenge length $len")
        val challenge = bytes(len)
        output.write(DbgProtocol.authResponse(challenge))
        output.flush()
        expectOk("scan unlock (answer refused)")
    }

    /**
     * First scan of [length] bytes from [address] in process [pid]. [data] is what the compare type needs (the value,
     * or low then high for "between"); it is only sent when the server reads it. Every match goes to [onMatch] as its
     * offset from [address] and the buffer holding its current value at [at]. Needs [unlockScans] first.
     */
    fun scanStart(
        pid: Int, address: Long, length: Int, type: ValueType, cmp: Int, data: ByteArray,
        onMatch: (offset: Int, buf: ByteArray, at: Int) -> Unit
    ) {
        val sendValue = DbgProtocol.startNeedsValue(cmp)
        val lenData = if (sendValue) data.size else type.size // never 0: the server refuses that
        val body = DbgProtocol.le(23).putInt(pid).putLong(address).putInt(length)
            .put(type.wire.toByte()).put(cmp.toByte()).put(type.size.toByte()).putInt(lenData).array()
        send(DbgProtocol.CMD_SCAN_START, body)
        expectOk("scan")
        if (sendValue) {
            output.write(data)
            output.flush()
        }
        expectOk("scan")
        readBlocks(type.size, onMatch)
        expectOk("scan")
    }

    /**
     * Next scan: the server re-reads each candidate and keeps the ones that still match. [offsets] (ascending, from
     * [base]) and, when the compare type needs them, [previous] values (type.size bytes each, in the same order) are
     * the candidates. [count] of them are used. Survivors go to [onMatch] with their new value.
     */
    fun scanCount(
        pid: Int, base: Long, type: ValueType, cmp: Int, data: ByteArray,
        offsets: IntArray, previous: ByteArray, count: Int,
        onMatch: (offset: Int, buf: ByteArray, at: Int) -> Unit
    ) {
        val sendValue = DbgProtocol.countNeedsValue(cmp)
        val withPrev = DbgProtocol.countNeedsPrevious(cmp)
        val lenData = if (sendValue) data.size else 0
        val body = DbgProtocol.le(18).putInt(pid).putLong(base).put(type.wire.toByte()).put(cmp.toByte()).putInt(lenData).array()
        send(DbgProtocol.CMD_SCAN_COUNT, body)
        expectOk("next scan")
        if (sendValue && lenData > 0) output.write(data)
        val entry = if (withPrev) 4 + type.size else 4
        val perChunk = DbgProtocol.COUNT_CHUNK / entry
        var i = 0
        while (i < count) {
            val n = minOf(perChunk, count - i)
            val chunk = DbgProtocol.le(4 + n * entry)
            chunk.putInt(n * entry)
            for (k in i until i + n) {
                chunk.putInt(offsets[k])
                if (withPrev) chunk.put(previous, k * type.size, type.size)
            }
            output.write(chunk.array())
            output.flush()
            readBlocks(type.size, onMatch)
            i += n
        }
        output.write(DbgProtocol.le(4).putInt(-1).array()) // no more candidates
        output.flush()
        expectOk("next scan")
    }

    /** Result blocks: u64 length, then (u32 offset, value) records, until the all-ones sentinel. */
    private fun readBlocks(valueSize: Int, onMatch: (Int, ByteArray, Int) -> Unit) {
        val rec = 4 + valueSize
        while (true) {
            val len = wrap(8).long
            if (len == DbgProtocol.SENTINEL) return
            if (len < 0 || len > 0x100000 || len % rec != 0L) throw DebuggerException("scan: garbled result block ($len bytes)")
            val block = bytes(len.toInt())
            val buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            var at = 0
            while (at < block.size) {
                onMatch(buf.getInt(at), block, at + 4)
                at += rec
            }
        }
    }

    /** Writes several (address, bytes) pairs in one request (capability 1.0 and newer). Throws if any failed. */
    fun writeMulti(pid: Int, entries: List<Pair<Long, ByteArray>>) {
        if (entries.isEmpty()) return
        send(DbgProtocol.CMD_WRITE_MULTI, DbgProtocol.le(12).putInt(pid).putInt(entries.size).putInt(0).array())
        expectOk("write")
        for ((addr, data) in entries) {
            output.write(DbgProtocol.le(12).putLong(addr).putInt(data.size).array())
            output.write(data)
        }
        output.flush()
        expectOk("write")
    }

    /** Where process [pid]'s first mapped region starts: the load address of a game's eboot.bin. */
    fun firstMapStart(pid: Int): Long {
        send(DbgProtocol.CMD_FIRST_MAP, DbgProtocol.le(4).putInt(pid).array())
        expectOk("load address")
        return wrap(8).long
    }

    /**
     * Looks for [pattern] (bytes where [mask] is 1 must match; the rest may be anything) in [length] bytes from
     * [address]. Gives the address when it occurs exactly once there, 0 when it doesn't occur or occurs more often.
     */
    fun findUnique(pid: Int, address: Long, length: Int, pattern: ByteArray, mask: ByteArray): Long {
        val body = DbgProtocol.le(22).putInt(pid).putLong(address).putInt(length).put(1.toByte()).put(1.toByte()).putInt(pattern.size).array()
        send(DbgProtocol.CMD_SCAN_AOB, body)
        expectOk("pattern search")
        output.write(pattern)
        output.write(mask)
        output.flush()
        expectOk("pattern search")
        val found = wrap(8).long
        expectOk("pattern search")
        return found
    }

    /** The turbo engines the server has (TSE_* bits), or 0. Only ask ps5debug-NG 1.3 and newer: older ones never answer. */
    fun turboEngines(): Int {
        send(DbgProtocol.CMD_TURBO_CAPS)
        expectOk("scan engines")
        val b = wrap(16)
        b.int // version
        return b.int
    }

    /** Every readable region of [pid] with its measured read speed (needs [unlockScans]). */
    fun regionSpeeds(pid: Int): List<DbgRegionSpeed> {
        send(DbgProtocol.CMD_TURBO_REGIONS, DbgProtocol.le(16).putInt(pid).putInt(8192).putInt(0).putInt(0).array())
        expectOk("region speeds")
        val n = count("region speeds", 8192)
        val raw = bytes(n * 32)
        expectOk("region speeds")
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until n).map { i ->
            val at = i * 32
            DbgRegionSpeed(b.getLong(at), b.getLong(at + 8), b.getInt(at + 16), b.getInt(at + 20) and 1 != 0, b.getInt(at + 24))
        }
    }

    /**
     * Remembers every non-zero [type] value in [segments] of process [pid] on the console (an "unknown starting
     * value" search). The values stay on the console, tied to this connection. Gives how many were remembered,
     * or -1 when the console could not keep them (out of space).
     */
    fun snapshotStart(pid: Int, type: ValueType, segments: List<ScanSegment>, progress: (Long, Long) -> Unit): Long {
        val flags = DbgProtocol.TS_SNAPSHOT or DbgProtocol.TS_SNAPSHOT_SEGMENTS
        val body = DbgProtocol.le(27).putInt(pid).putLong(0).putInt(0)
            .put(type.wire.toByte()).put(DbgProtocol.CMP_UNKNOWN.toByte()).put(type.size.toByte()).putInt(0).putInt(flags).array()
        send(DbgProtocol.CMD_TURBO_START, body)
        expectOk("snapshot")
        expectOk("snapshot")
        val list = DbgProtocol.le(4 + 12 * segments.size).putInt(segments.size)
        for (seg in segments) list.putLong(seg.base).putInt(seg.length)
        output.write(list.array())
        output.flush()
        val plan = wrap(16)
        plan.long // slots
        val total = plan.long
        while (true) {
            val done = wrap(8).long
            if (done == DbgProtocol.SENTINEL) break
            progress(done, total)
        }
        val sum = wrap(12)
        val ok = sum.int
        val survivors = sum.long
        expectOk("snapshot")
        return if (ok == 0) -1 else survivors
    }

    /** Narrows the search kept on the console with [cmp] (and its [data]). Gives how many are left. */
    fun residentNext(pid: Int, type: ValueType, cmp: Int, data: ByteArray): Long {
        val sendValue = DbgProtocol.countNeedsValue(cmp)
        val lenData = if (sendValue) data.size else 0
        val body = DbgProtocol.le(22).putInt(pid).putLong(0).put(type.wire.toByte()).put(cmp.toByte()).putInt(lenData)
            .putInt(DbgProtocol.TS_SERVER_RESIDENT).array()
        send(DbgProtocol.CMD_TURBO_COUNT, body)
        expectOk("next scan")
        if (lenData > 0) {
            output.write(data)
            output.flush()
        }
        while (wrap(8).long != DbgProtocol.SENTINEL) {
            // progress records; the count comes after them
        }
        val left = wrap(8).long
        expectOk("next scan")
        return left
    }

    /** [count] survivors of the search kept on the console, from number [start]. */
    fun residentGet(start: Int, count: Int, type: ValueType): List<DbgSurvivor> {
        send(DbgProtocol.CMD_TURBO_GET, DbgProtocol.le(12).putInt(start).putInt(count).putInt(0).array())
        expectOk("matches")
        val hdr = u32()
        val n = hdr and 0x7FFFFFFF
        val values = if (hdr and 0x80000000.toInt() != 0) 3 else 2
        if (n > count) throw DebuggerException("matches: got $n, asked for $count")
        val rec = 8 + values * type.size
        val raw = bytes(n * rec)
        expectOk("matches")
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until n).map { i ->
            val at = i * rec
            DbgSurvivor(
                b.getLong(at),
                raw.copyOfRange(at + 8, at + 8 + type.size),
                raw.copyOfRange(at + 8 + type.size, at + 8 + 2 * type.size)
            )
        }
    }

    /** Lets the console forget the search it kept for this connection. */
    fun residentEnd() {
        send(DbgProtocol.CMD_TURBO_END)
        expectOk("end search")
    }

    /** Reads [size] bytes at each of the first [count] [addresses] in one request. Needs [unlockScans] first. */
    fun readMany(pid: Int, addresses: LongArray, count: Int, size: Int): ByteArray {
        if (count == 0) return ByteArray(0)
        val body = DbgProtocol.le(8).putInt(pid).putInt(count).array()
        send(DbgProtocol.CMD_SCAN_GET, body)
        expectOk("read values")
        val list = DbgProtocol.le(12 * count)
        for (k in 0 until count) list.putLong(addresses[k]).putInt(size)
        output.write(list.array())
        output.flush()
        val out = bytes(count * size)
        if (wrap(8).long != DbgProtocol.SENTINEL) throw DebuggerException("read values: garbled reply")
        return out
    }
}

/** Opens a connection, runs [block] with it and closes it again. Blocking: call from Dispatchers.IO. */
fun <T> withDebugger(host: String, port: Int = DEBUG_PORT, block: (Ps5Debug) -> T): T =
    Ps5Debug(host, port).use(block)

// ---------------------------------------------------------------------------------------------
// Showing memory
// ---------------------------------------------------------------------------------------------

/** An address as 0x0000004B3A8C0000-style text, without leading zeros past 9 digits so it fits on a phone. */
fun hexAddr(a: Long): String = "0x" + java.lang.Long.toHexString(a).uppercase().padStart(9, '0')

/**
 * The classic hex view: one line per [perLine] bytes, "address  bytes  characters", e.g.
 *   4B3A8C0000  7F 45 4C 46 02 01 01 00  .ELF....
 */
fun hexDump(base: Long, data: ByteArray, perLine: Int = 8): List<String> {
    val lines = ArrayList<String>((data.size + perLine - 1) / perLine)
    var i = 0
    while (i < data.size) {
        val end = minOf(i + perLine, data.size)
        val sb = StringBuilder()
        sb.append(java.lang.Long.toHexString(base + i).uppercase().padStart(10, '0')).append("  ")
        for (j in i until i + perLine) {
            if (j < end) sb.append("%02X ".format(data[j].toInt() and 0xFF)) else sb.append("   ")
        }
        sb.append(' ')
        for (j in i until end) {
            val c = data[j].toInt() and 0xFF
            sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
        lines.add(sb.toString())
        i = end
    }
    return lines
}
