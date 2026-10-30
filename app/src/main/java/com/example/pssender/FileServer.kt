package com.example.pssender

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------------------------
// A small web server that hands out files from the phone, so the console can download a package from the phone
// ("Install from this phone"). Only plain Java classes are used here (no Android parts), so all of it can be
// tested on its own.
//
// What it understands: GET and HEAD for /<token>/<name>, with one byte range per request (the console resumes and
// seeks with them). Every connection serves one request and is closed afterwards. Nothing else is answered, and
// there is no page that lists the files, so only a device that was given the link can download.
// ---------------------------------------------------------------------------------------------

/** How long the server waits without any download before it stops by itself (30 minutes). */
const val SERVE_IDLE_MS = 30L * 60L * 1000L

/** A download that has not moved a single byte for this long is dropped, so a console that vanished frees its connection. */
const val SERVE_STALL_MS = 60L * 1000L

/** The longest a connection may take to send its request. */
private const val REQUEST_TIMEOUT_MS = 15_000

private const val CHUNK_SIZE = 64 * 1024
private const val MAX_LINE_BYTES = 8192
private const val MAX_HEADER_LINES = 64

/** Progress is reported to the screen at most this often. */
private const val REPORT_EVERY_MS = 250L

/** Gives out the bytes of a file from a position on. Each call opens its own stream, which the server closes. */
fun interface ByteSource {
    fun open(from: Long): InputStream
}

/**
 * A file that the server hands out. Its link is /[token]/[name]; [token] is a secret that nobody can guess, [name]
 * only has to look like a file name. [size] is the exact size in bytes.
 */
class SharedFile(val token: String, val name: String, val size: Long, val source: ByteSource) {
    /** Tells the console whether it is still downloading the same file when it resumes (it goes with Range requests). */
    val etag: String = "\"" + size.toString(16) + "-" + token.take(8) + "\""
}

/** What the server tells the app about. All calls come from the server's own threads. */
interface ServerEvents {
    /** [count] connections are open now. */
    fun connections(count: Int) {}

    /** [covered] bytes of [file] have been handed out at least once (a repeated range doesn't count twice). */
    fun progress(file: SharedFile, covered: Long) {}

    /** Every byte of [file] has been handed out. */
    fun completed(file: SharedFile) {}

    /** Something went wrong with [file] (null: with no particular file). The text is for developers. */
    fun problem(file: SharedFile?, message: String) {}

    /** The server stopped by itself because nothing was downloaded for a long time. */
    fun idleStop() {}
}

// ---------------------------------------------------------------------------------------------
// Which bytes were handed out
// ---------------------------------------------------------------------------------------------

/**
 * Remembers which parts of a file of [size] bytes were handed out, as a set of separate ranges that are joined when they
 * touch. [covered] is how many different bytes that is, so it never goes above [size], however often a part is asked for.
 */
class Coverage(val size: Long) {
    private val parts = TreeMap<Long, Long>() // start -> end (end not included)
    private var total = 0L

    /** The number of different bytes handed out so far. */
    @get:Synchronized
    val covered: Long get() = total

    /** True when every byte was handed out. */
    @get:Synchronized
    val complete: Boolean get() = total >= size

    /** Notes that the bytes from [from] up to, but not including, [to] were handed out. */
    @Synchronized
    fun add(from: Long, to: Long) {
        var start = from.coerceIn(0L, size)
        var end = to.coerceIn(0L, size)
        if (end <= start) return
        val before = parts.floorEntry(start)
        if (before != null && before.value >= start) {
            // The range before this one reaches it or touches it: they become one
            start = before.key
            if (before.value > end) end = before.value
        }
        // Take in every range that starts inside the new one
        while (true) {
            val next = parts.ceilingEntry(start) ?: break
            if (next.key > end) break
            if (next.value > end) end = next.value
            total -= next.value - next.key
            parts.remove(next.key)
        }
        parts[start] = end
        total += end - start
    }
}

// ---------------------------------------------------------------------------------------------
// The Range header
// ---------------------------------------------------------------------------------------------

/** What to send for a request, after looking at its Range header. */
sealed class RangeChoice {
    /** The whole file (no Range header, or one that is ignored). */
    object Whole : RangeChoice()

    /** The bytes from [start] to [end], both included. */
    class Part(val start: Long, val end: Long) : RangeChoice()

    /** The range lies outside the file. */
    object Unsatisfiable : RangeChoice()
}

/**
 * Reads a Range header for a file of [size] bytes. Only the forms "bytes=a-b", "bytes=a-" and "bytes=-n" are served.
 * Anything else (another unit, several ranges, text that makes no sense) is ignored, which means the whole file is
 * sent, as the HTTP rules allow.
 */
fun parseRange(header: String?, size: Long): RangeChoice {
    val text = header?.trim() ?: return RangeChoice.Whole
    if (text.length < 6 || !text.regionMatches(0, "bytes=", 0, 6, ignoreCase = true)) return RangeChoice.Whole
    val spec = text.substring(6).trim()
    if (spec.isEmpty() || spec.contains(',')) return RangeChoice.Whole
    val dash = spec.indexOf('-')
    if (dash < 0) return RangeChoice.Whole
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()
    if (!first.all { it in '0'..'9' } || !last.all { it in '0'..'9' }) return RangeChoice.Whole
    if (first.isEmpty()) {
        // "-n": the last n bytes
        if (last.isEmpty()) return RangeChoice.Whole
        val n = last.toLongOrNull() ?: return RangeChoice.Whole
        if (n == 0L || size == 0L) return RangeChoice.Unsatisfiable
        return RangeChoice.Part(maxOf(0L, size - n), size - 1)
    }
    val a = first.toLongOrNull() ?: return RangeChoice.Whole
    if (last.isEmpty()) {
        return if (a >= size) RangeChoice.Unsatisfiable else RangeChoice.Part(a, size - 1)
    }
    val b = last.toLongOrNull() ?: return RangeChoice.Whole
    if (b < a) return RangeChoice.Whole
    if (a >= size) return RangeChoice.Unsatisfiable
    return RangeChoice.Part(a, minOf(b, size - 1))
}

// ---------------------------------------------------------------------------------------------
// The server
// ---------------------------------------------------------------------------------------------

/**
 * Hands out [files] over HTTP. Call [start] to open the port and [stop] to close everything. It stops itself after
 * [idleMillis] without any download (and then calls [ServerEvents.idleStop]), and drops a download that has not moved
 * for [stallMillis]. At most [maxConnections] connections are served at once; more get a "busy" answer.
 * [clock] returns milliseconds and [tickMillis] is how often the server looks for stalled and idle connections
 * (both can be changed to make tests fast).
 */
class FileServer(
    files: List<SharedFile>,
    private val events: ServerEvents,
    private val idleMillis: Long = SERVE_IDLE_MS,
    private val stallMillis: Long = SERVE_STALL_MS,
    private val maxConnections: Int = 16,
    private val tickMillis: Long = 1000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private class Shared(val file: SharedFile) {
        val coverage = Coverage(file.size)
        var lastReport = 0L
        var reported = false
        var finished = false
    }

    private class Conn(val socket: Socket, now: Long) {
        @Volatile
        var writing = false

        @Volatile
        var lastProgress = now
    }

    private class BadRequest(message: String) : Exception(message)

    private class Request(val method: String, val target: String, val headers: Map<String, String>)

    private val byToken: Map<String, Shared> = files.associate { it.token to Shared(it) }
    private val connections = HashSet<Conn>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastActivity = 0L

    @Volatile
    private var listener: ServerSocket? = null

    @Volatile
    private var pool: ThreadPoolExecutor? = null

    private var acceptThread: Thread? = null
    private var watchThread: Thread? = null

    /** The port the server listens on (0 until it is started). */
    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    /**
     * Starts listening on [bindTo] (null: every address), at [preferredPort] if that is free and otherwise at a port
     * that the system picks. Returns the port. Throws when nothing can be bound.
     */
    fun start(bindTo: InetAddress?, preferredPort: Int): Int {
        check(running.compareAndSet(false, true)) { "The server is already started" }
        try {
            val socket = openListener(bindTo, preferredPort)
            listener = socket
            port = socket.localPort
            lastActivity = clock()
            pool = ThreadPoolExecutor(0, maxConnections, 20L, TimeUnit.SECONDS, SynchronousQueue()) { r ->
                Thread(r, "files-serve").also { it.isDaemon = true }
            }
            acceptThread = Thread({ acceptLoop(socket) }, "files-accept").also { it.isDaemon = true; it.start() }
            watchThread = Thread({ watchLoop() }, "files-watch").also { it.isDaemon = true; it.start() }
            return port
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
    }

    private fun openListener(bindTo: InetAddress?, preferredPort: Int): ServerSocket {
        fun attempt(p: Int): ServerSocket {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(bindTo, p), 50)
                return s
            } catch (e: IOException) {
                closeQuietly(s)
                throw e
            }
        }
        return try {
            attempt(preferredPort)
        } catch (e: BindException) {
            if (preferredPort == 0) throw e
            attempt(0) // the usual port is taken: let the system pick one
        }
    }

    /** Closes the port and every connection. Safe to call more than once, and from any thread. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeQuietly(listener)
        val open = synchronized(connections) { ArrayList(connections) }
        for (c in open) closeQuietly(c.socket)
        pool?.shutdownNow()
        watchThread?.interrupt()
    }

    // ----- connections -----

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!running.get()) break
                try {
                    Thread.sleep(50) // a passing problem (for example too many open files): don't spin
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            val conn = Conn(client, clock())
            val count = synchronized(connections) { connections.add(conn); connections.size }
            lastActivity = clock()
            events.connections(count)
            try {
                val workers = pool
                if (workers == null) {
                    drop(conn)
                    closeQuietly(client)
                } else {
                    workers.execute { handle(conn) }
                }
            } catch (e: RejectedExecutionException) {
                busyAnswer(client)
                drop(conn)
                closeQuietly(client)
            }
        }
    }

    /** Takes [conn] out of the list of open connections and tells the app. */
    private fun drop(conn: Conn) {
        val count = synchronized(connections) { connections.remove(conn); connections.size }
        lastActivity = clock()
        events.connections(count)
    }

    private fun busyAnswer(client: Socket) {
        try {
            val text = "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            client.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
        } catch (_: IOException) {
        }
    }

    /** Looks for downloads that stalled, and stops the server when it has been idle for too long. */
    private fun watchLoop() {
        while (running.get()) {
            try {
                Thread.sleep(tickMillis)
            } catch (_: InterruptedException) {
                return
            }
            val now = clock()
            val open = synchronized(connections) { ArrayList(connections) }
            for (c in open) {
                if (c.writing && now - c.lastProgress > stallMillis) closeQuietly(c.socket)
            }
            if (open.isEmpty() && now - lastActivity > idleMillis) {
                stop()
                Thread.interrupted() // stop() interrupted this very thread; the app's callback must not be disturbed by that
                events.idleStop()
                return
            }
        }
    }

    private fun handle(conn: Conn) {
        val socket = conn.socket
        try {
            socket.soTimeout = REQUEST_TIMEOUT_MS
            socket.tcpNoDelay = true
            val out = socket.getOutputStream()
            val request = try {
                readRequest(BufferedInputStream(socket.getInputStream(), 4096))
            } catch (e: BadRequest) {
                answer(out, 400, "Bad Request", "Bad request")
                return
            } ?: return // the client closed without asking for anything
            respond(conn, request, out)
        } catch (_: IOException) {
            // The console went away or the server was stopped: nothing to report
        } catch (e: RuntimeException) {
            events.problem(null, "Unexpected error: " + e.javaClass.simpleName + " " + (e.message ?: ""))
        } finally {
            drop(conn)
            lingeringClose(socket)
        }
    }

    // ----- reading the request -----

    /** The request line and the headers. Null when the client closed the connection before it sent anything. */
    private fun readRequest(input: InputStream): Request? {
        var line = readLine(input) ?: return null
        var blanks = 0
        while (line.isEmpty()) { // a stray empty line before the request is tolerated, as the HTTP rules advise
            if (++blanks > 2) throw BadRequest("empty request")
            line = readLine(input) ?: return null
        }
        val parts = line.split(' ')
        if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty() || !parts[2].startsWith("HTTP/")) {
            throw BadRequest("bad request line")
        }
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            if (++count > MAX_HEADER_LINES) throw BadRequest("too many headers")
            val colon = h.indexOf(':')
            if (colon <= 0) throw BadRequest("bad header")
            val name = h.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = h.substring(colon + 1).trim()
            headers[name] = if (headers.containsKey(name)) headers[name] + ", " + value else value
        }
        return Request(parts[0].uppercase(Locale.ROOT), parts[1], headers)
    }

    /** One line without its line ending. Null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (bytes.size() >= MAX_LINE_BYTES) throw BadRequest("line too long")
            bytes.write(b)
        }
        val raw = bytes.toByteArray()
        val n = if (raw.isNotEmpty() && raw[raw.size - 1] == '\r'.code.toByte()) raw.size - 1 else raw.size
        return String(raw, 0, n, Charsets.ISO_8859_1)
    }

    // ----- answering -----

    private fun respond(conn: Conn, request: Request, out: OutputStream) {
        val head = request.method == "HEAD"
        if (request.method != "GET" && !head) {
            answer(out, 405, "Method Not Allowed", "Method not allowed", "Allow: GET, HEAD")
            return
        }
        val shared = find(request.target)
        if (shared == null) {
            answer(out, 404, "Not Found", "Not found")
            return
        }
        val file = shared.file
        var choice = parseRange(request.headers["range"], file.size)
        val ifRange = request.headers["if-range"]
        if (ifRange != null && ifRange.trim() != file.etag) choice = RangeChoice.Whole // not the file the client started with

        if (choice is RangeChoice.Unsatisfiable) {
            val text = "HTTP/1.1 416 Range Not Satisfiable\r\n" + commonHeaders() +
                "Content-Range: bytes */${file.size}\r\nContent-Length: 0\r\n\r\n"
            out.write(text.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            return
        }
        val start: Long
        val end: Long
        val partial: Boolean
        if (choice is RangeChoice.Part) {
            start = choice.start
            end = choice.end
            partial = true
        } else {
            start = 0L
            end = file.size - 1
            partial = false
        }
        val length = end - start + 1

        val input: InputStream? = if (head || length <= 0L) {
            null
        } else {
            try {
                file.source.open(start)
            } catch (e: Exception) {
                events.problem(file, "Can't open the file: " + (e.message ?: e.javaClass.simpleName))
                answer(out, 500, "Internal Server Error", "Can't read the file")
                return
            }
        }
        try {
            val sb = StringBuilder()
            sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append(commonHeaders())
            sb.append("Content-Type: application/octet-stream\r\n")
            sb.append("Content-Length: ").append(length).append("\r\n")
            if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(file.size).append("\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("ETag: ").append(file.etag).append("\r\n")
            sb.append("Content-Disposition: attachment; filename=\"").append(headerSafe(file.name)).append("\"\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (input == null) {
                out.flush()
                return
            }
            conn.writing = true
            conn.lastProgress = clock()
            stream(conn, shared, input, start, length, out)
        } finally {
            if (input != null) closeQuietly(input)
        }
    }

    /** Sends [length] bytes of the file from [start]. Ends the connection with an error if the file turns out shorter. */
    private fun stream(conn: Conn, shared: Shared, input: InputStream, start: Long, length: Long, out: OutputStream) {
        val file = shared.file
        val buffer = ByteArray(CHUNK_SIZE)
        var position = start
        var left = length
        try {
            while (left > 0L) {
                val n = try {
                    input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                } catch (e: IOException) {
                    events.problem(file, "Can't read the file: " + (e.message ?: e.javaClass.simpleName))
                    throw e
                }
                if (n < 0) {
                    events.problem(file, "The file ended before byte " + (position) + " (it was " + file.size + " bytes long)")
                    throw IOException("file ended early") // the console must see a broken download, not a short file
                }
                out.write(buffer, 0, n)
                conn.lastProgress = clock()
                shared.coverage.add(position, position + n)
                position += n
                left -= n
                report(shared, false)
            }
            out.flush()
        } finally {
            report(shared, true)
        }
    }

    /** Tells the app how far the file got (at most a few times a second, unless [force]) and when it is complete. */
    private fun report(shared: Shared, force: Boolean) {
        val now = clock()
        // Under the lock, so the reports of one file reach the app in the order they were made (a late, lower number
        // from a slower connection can't overwrite a higher one)
        synchronized(shared) {
            val covered = shared.coverage.covered
            var send = force || !shared.reported || now - shared.lastReport >= REPORT_EVERY_MS
            val finishedNow = !shared.finished && shared.coverage.complete
            if (finishedNow) {
                shared.finished = true
                send = true
            }
            if (send) {
                shared.reported = true
                shared.lastReport = now
                events.progress(shared.file, covered)
            }
            if (finishedNow) events.completed(shared.file)
        }
    }

    /** The file that a request target (like /token/name?x=1 or a full http:// address) points to. */
    private fun find(target: String): Shared? {
        var path = target
        if (path.startsWith("http://", ignoreCase = true)) {
            val slash = path.indexOf('/', 7)
            path = if (slash < 0) "/" else path.substring(slash)
        }
        val cut = path.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) path = path.substring(0, cut)
        val token = path.split('/').firstOrNull { it.isNotEmpty() } ?: return null
        return byToken[token]
    }

    private fun answer(out: OutputStream, code: Int, reason: String, text: String, extra: String? = null) {
        val body = text.toByteArray(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        sb.append(commonHeaders())
        if (extra != null) sb.append(extra).append("\r\n")
        sb.append("Content-Type: text/plain\r\nContent-Length: ").append(body.size).append("\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun commonHeaders(): String =
        "Date: " + httpDate() + "\r\nCache-Control: no-store\r\nConnection: close\r\n"

    private fun httpDate(): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date())
    }

    companion object {
        /** The port that is tried first, a common one for simple web servers; another is used when it is taken. */
        const val PREFERRED_PORT = 8000
    }
}

/**
 * Closes a connection politely: tells the client that nothing more comes, then waits a moment for it to hang up. Closing
 * at once while the client is still sending would reset the connection, and the client might then lose the end of the
 * answer (for example the reason for a refusal).
 */
private fun lingeringClose(socket: Socket) {
    try {
        socket.shutdownOutput()
        socket.soTimeout = 200
        val input = socket.getInputStream()
        val scratch = ByteArray(1024)
        val end = System.nanoTime() + 300_000_000L
        var budget = 64 * 1024
        while (budget > 0 && System.nanoTime() < end) {
            val n = input.read(scratch)
            if (n < 0) break
            budget -= n
        }
    } catch (_: IOException) {
    } finally {
        closeQuietly(socket)
    }
}

private fun closeQuietly(c: Closeable?) {
    try {
        c?.close()
    } catch (_: IOException) {
    }
}

/** [name] for use inside a quoted header value: printable ASCII only, no quotes, backslashes or spaces. */
private fun headerSafe(name: String): String =
    name.map { if (it.code in 33..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
