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
data class DbgInfo(val brand: String, val protocol: String, val firmware: String)

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

    /** "ps5debug-NG by OSR v1.3.2 [...]". Anything after a zero byte is a capability level and is left out. (No status word.) */
    fun branding(): String {
        send(DbgProtocol.CMD_BRANDING)
        val len = count("branding", 4096)
        val b = bytes(len)
        return DbgProtocol.cString(b, 0, b.size)
    }

    fun info(): DbgInfo = DbgInfo(brand = branding(), protocol = protocolVersion(), firmware = DbgProtocol.firmwareText(firmware()))

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
