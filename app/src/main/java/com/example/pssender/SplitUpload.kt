package com.example.pssender

import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// ---------------------------------------------------------------------------------------------
// Transfer speed settings, and splitting one big upload into parts that go to the console at the same time.
//
// A split upload writes each part into the same file on the console, from its own place (REST, then STOR). That only
// works when the console's FTP server keeps what is already in the file when a part starts at a later place, so it is
// switched on per console and only after [splitSelfTest] wrote a test file in parts and read it back unchanged.
// The first part opens the file first (that is the one that empties it); the others start once it is open.
// ---------------------------------------------------------------------------------------------

/** Files smaller than this go in one piece: splitting saves too little on them. */
const val SPLIT_MIN = 128L * 1024 * 1024

/** How many parts a big upload goes in, once the console passed the test. */
const val SPLIT_PARTS = 4

/** The test file: big enough that every part moves real data. */
const val SPLIT_TEST_SIZE = 8 * 1024 * 1024

/** A part that moves nothing for this long counts as stuck. */
private const val SPLIT_STALL_MS = 60_000L

/** The speed settings that are not the speed limit or files at once. */
object TransferTuning {
    private const val PIECES = "web_pieces"
    private const val SPLIT_DL = "split_downloads"
    private const val SPLIT_FOR = "split_ok_for"

    /** Web files come as several pieces at once (see [PieceStream]). On unless turned off. */
    @Volatile
    var webPieces = true

    /** Big files from the console come down in parts at once (see [splitDownload]). On unless turned off. */
    @Volatile
    var splitDownloads = true

    /** The console ("host:port") that passed the split test, or "" when splitting is off. */
    @Volatile
    var splitOkFor = ""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        webPieces = prefs(ctx).getBoolean(PIECES, true)
        splitDownloads = prefs(ctx).getBoolean(SPLIT_DL, true)
        splitOkFor = prefs(ctx).getString(SPLIT_FOR, "") ?: ""
    }

    fun setWebPieces(ctx: Context, on: Boolean) {
        webPieces = on
        prefs(ctx).edit().putBoolean(PIECES, on).apply()
    }

    fun setSplitDownloads(ctx: Context, on: Boolean) {
        splitDownloads = on
        prefs(ctx).edit().putBoolean(SPLIT_DL, on).apply()
    }

    fun setSplitFor(ctx: Context, console: String) {
        splitOkFor = console
        prefs(ctx).edit().putString(SPLIT_FOR, console).apply()
    }

    /** How many parts a big upload to [s] goes in: [SPLIT_PARTS] for the console that passed the test, else 1. */
    fun partsFor(s: FtpSettings): Int = if (splitOkFor.isNotEmpty() && splitOkFor == consoleKey(s)) SPLIT_PARTS else 1

    fun consoleKey(s: FtpSettings): String = "${s.host}:${s.port}"
}

/** A source that can be read from any place at once by several readers, and knows its size. */
interface SplittableSource : ResumableSource {
    /** The size in bytes, or -1 when it isn't known. */
    fun size(): Long
}

/** Where each of [parts] parts of a file of [total] bytes starts and how long it is. */
fun splitBounds(total: Long, parts: Int): List<LongRange> {
    if (total <= 0L || parts <= 1) return listOf(0L until maxOf(total, 0L))
    val each = (total + parts - 1) / parts
    return (0 until parts).mapNotNull { i ->
        val start = i * each
        if (start >= total) null else start until minOf(total, start + each)
    }
}

/**
 * Sends [source] ([total] bytes) to [remote] in [parts] parts at the same time, each on its own connection. Null when it
 * worked; otherwise why not (the caller then sends the file the usual way, from the start).
 */
internal fun splitUpload(
    source: ResumableSource, total: Long, remote: String, s: FtpSettings, parts: Int, hooks: TransferHooks
): String? {
    val bounds = splitBounds(total, parts)
    val sessions = bounds.map { FtpSession() }
    val done = AtomicLong(0)
    val failure = AtomicReference<String?>(null)
    val abortAll = {
        sessions.forEach { it.abort() }
        source.abort()
    }
    hooks.setAbort(abortAll)
    hooks.starting(0L, total)
    try {
        for (session in sessions) {
            sessionLogin(session, s)?.let { return it.message }
        }
        // The first part opens (and empties) the file before the others write into it
        val firstOut = openPart(sessions[0], remote, 0L)
        val workers = bounds.mapIndexed { i, range ->
            thread(name = "split-$i", isDaemon = true) {
                try {
                    val out = if (i == 0) firstOut else openPart(sessions[i], remote, range.first)
                    sendPart(sessions[i], source, range, out, done)
                } catch (e: Exception) {
                    failure.compareAndSet(null, describeFailure(e))
                    abortAll()
                }
            }
        }
        var last = -1L
        var lastMove = System.currentTimeMillis()
        while (workers.any { it.isAlive }) {
            workers.firstOrNull { it.isAlive }?.join(300)
            val now = done.get()
            hooks.progress(now, total)
            if (now != last) {
                last = now
                lastMove = System.currentTimeMillis()
            }
            if (hooks.cancelled || hooks.paused) {
                abortAll()
                return AppText.get(R.string.err_cancelled_upload, remote)
            }
            if (System.currentTimeMillis() - lastMove > SPLIT_STALL_MS) {
                failure.compareAndSet(null, AppText.get(R.string.split_stuck))
                abortAll()
            }
        }
        failure.get()?.let { return it }
        // Every part said it was stored: the file must now be exactly as long as the source
        val check = FtpSession()
        try {
            sessionLogin(check, s)?.let { return it.message }
            val size = remoteSizeOf(check.ftp, remote)
            if (size != total) return AppText.get(R.string.split_wrong_size, size.toString(), total.toString())
        } finally {
            check.abort()
        }
        hooks.progress(total, total)
        return null
    } catch (e: Exception) {
        return describeFailure(e)
    } finally {
        hooks.setAbort(null)
        for (session in sessions) {
            try {
                if (session.ftp.isConnected) {
                    session.ftp.soTimeout = 3000
                    session.ftp.logout()
                }
            } catch (_: Exception) {
            }
            session.abort()
        }
    }
}

/** Starts storing [remote] from byte [start] on [session]'s connection. */
private fun openPart(session: FtpSession, remote: String, start: Long): OutputStream {
    val ftp = session.ftp
    if (start > 0L) ftp.setRestartOffset(start)
    return ftp.storeFileStream(remote) ?: throw IOException(ftp.replyString?.trim().orEmpty())
}

/** Sends the bytes of [range] from [source] through [out], then waits for the console to confirm the part. */
private fun sendPart(session: FtpSession, source: ResumableSource, range: LongRange, out: OutputStream, done: AtomicLong) {
    val opened = source.open(range.first)
    if (opened.startsAt != range.first) {
        opened.input.close()
        throw IOException("source can't start at ${range.first}")
    }
    var left = range.last - range.first + 1
    val buf = ByteArray(256 * 1024)
    opened.input.use { input ->
        out.use { o ->
            while (left > 0L) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("source ended early")
                o.write(buf, 0, n)
                left -= n
                done.addAndGet(n.toLong())
            }
        }
    }
    if (!session.ftp.completePendingCommand()) throw IOException(session.ftp.replyString?.trim().orEmpty())
}

/** Bytes in memory as a [SplittableSource] (the split test's file). */
class BytesSource(private val bytes: ByteArray) : SplittableSource {
    override fun size(): Long = bytes.size.toLong()
    override fun open(offset: Long): OpenedSource {
        val at = offset.coerceIn(0L, bytes.size.toLong()).toInt()
        return OpenedSource(bytes.inputStream(at, bytes.size - at), bytes.size.toLong(), at.toLong())
    }
}

/** The split test's file: a pattern where every place has its own bytes, so a part written to the wrong place shows. */
fun splitTestBytes(size: Int = SPLIT_TEST_SIZE): ByteArray =
    ByteArray(size) { i -> ((i xor (i ushr 8) xor (i ushr 16) * 7) and 0xFF).toByte() }

/**
 * Writes a test file to [dir] on the console in parts, reads it back and compares, then deletes it. Null when the console
 * takes files in parts correctly; otherwise why not.
 */
fun splitSelfTest(s: FtpSettings, dir: String = "/data"): String? {
    val bytes = splitTestBytes()
    val remote = dir.trimEnd('/') + "/.crimson-split-test.bin"
    val hooks = object : TransferHooks {
        override val cancelled = false
        override fun starting(done: Long, total: Long) {}
        override fun progress(done: Long, total: Long) {}
        override fun status(text: String) {}
        override fun setAbort(hook: (() -> Unit)?) {}
    }
    splitUpload(BytesSource(bytes), bytes.size.toLong(), remote, s, SPLIT_PARTS, hooks)?.let { return it }
    val session = FtpSession()
    try {
        sessionLogin(session, s)?.let { return it.message }
        val back = session.ftp.retrieveFileStream(remote)?.use { readAll(it, bytes.size + 1) }
            ?: return session.ftp.replyString?.trim().orEmpty()
        session.ftp.completePendingCommand()
        try {
            session.ftp.deleteFile(remote)
        } catch (_: Exception) {
        }
        return if (back.contentEquals(bytes)) null else AppText.get(R.string.split_mixed_up)
    } catch (e: Exception) {
        return describeFailure(e)
    } finally {
        session.abort()
    }
}

// ---------------------------------------------------------------------------------------------
// Downloads in parts: each connection asks for the file from its own place (REST, then RETR), takes its stretch and
// hangs up; the phone's copy is written at each part's place. Any FTP server that continues downloads can do this.
// ---------------------------------------------------------------------------------------------

/** Files smaller than this come down in one piece. */
const val SPLIT_DL_MIN = 64L * 1024 * 1024

/** What [splitDownload] says when it can't be done here at all (nothing was tried, so there is nothing to tell). */
const val SPLIT_NOT_POSSIBLE = "-"

/**
 * Fetches [remote] ([size] bytes) into [sink] in [parts] parts at the same time. Null when it worked, [SPLIT_NOT_POSSIBLE]
 * when the sink can't be written in parts, otherwise why it didn't work (the caller then fetches it the usual way).
 */
internal fun splitDownload(
    sink: ResumableSink, remote: String, size: Long, s: FtpSettings, parts: Int, hooks: TransferHooks
): String? {
    val bounds = splitBounds(size, parts)
    if (bounds.size < 2) return SPLIT_NOT_POSSIBLE
    // Every part's place must be writable before anything is asked of the console
    val outs = ArrayList<OutputStream>()
    try {
        for (b in bounds) outs.add(sink.openAt(b.first) ?: run { outs.forEach { closeQuietly(it) }; return SPLIT_NOT_POSSIBLE })
    } catch (e: Exception) {
        outs.forEach { closeQuietly(it) }
        return SPLIT_NOT_POSSIBLE
    }
    val sessions = bounds.map { FtpSession() }
    val done = AtomicLong(0)
    val failure = AtomicReference<String?>(null)
    val abortAll = { sessions.forEach { it.abort() } }
    hooks.setAbort(abortAll)
    hooks.starting(0L, size)
    try {
        val workers = bounds.mapIndexed { i, range ->
            thread(name = "split-dl-$i", isDaemon = true) {
                try {
                    fetchPart(sessions[i], s, remote, range, outs[i], done, last = i == bounds.lastIndex)
                } catch (e: Exception) {
                    failure.compareAndSet(null, describeFailure(e))
                    abortAll()
                } finally {
                    closeQuietly(outs[i])
                }
            }
        }
        var last = -1L
        var lastMove = System.currentTimeMillis()
        while (workers.any { it.isAlive }) {
            workers.firstOrNull { it.isAlive }?.join(300)
            val now = done.get()
            hooks.progress(now, size)
            if (now != last) {
                last = now
                lastMove = System.currentTimeMillis()
            }
            if (hooks.cancelled || hooks.paused) {
                abortAll()
                return AppText.get(R.string.err_cancelled_download)
            }
            if (System.currentTimeMillis() - lastMove > SPLIT_STALL_MS) {
                failure.compareAndSet(null, AppText.get(R.string.split_stuck))
                abortAll()
            }
        }
        failure.get()?.let { return it }
        val stored = sink.storedLength()
        if (stored != size) return AppText.get(R.string.split_wrong_size, stored.toString(), size.toString())
        hooks.progress(size, size)
        return null
    } catch (e: Exception) {
        return describeFailure(e)
    } finally {
        hooks.setAbort(null)
        outs.forEach { closeQuietly(it) }
        sessions.forEach { it.abort() }
    }
}

/** One part of a download: logs in, starts the file at the part's place, copies exactly its stretch, then hangs up. */
private fun fetchPart(
    session: FtpSession, s: FtpSettings, remote: String, range: LongRange, out: OutputStream, done: AtomicLong, last: Boolean
) {
    sessionLogin(session, s)?.let { throw IOException(it.message) }
    val ftp = session.ftp
    if (range.first > 0L) ftp.setRestartOffset(range.first)
    // Null also when the console refused to start at that place (REST): then this can't be done in parts
    val input = ftp.retrieveFileStream(remote) ?: throw IOException(ftp.replyString?.trim().orEmpty())
    var left = range.last - range.first + 1
    val buf = ByteArray(256 * 1024)
    try {
        while (left > 0L) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("the console stopped sending early")
            out.write(buf, 0, n)
            left -= n
            done.addAndGet(n.toLong())
        }
    } finally {
        closeQuietly(input)
    }
    // The last part reads to the end of the file, so the console finishes normally; the others hang up early on purpose
    if (last && !ftp.completePendingCommand()) throw IOException(ftp.replyString?.trim().orEmpty())
}

private fun closeQuietly(c: java.io.Closeable) {
    try {
        c.close()
    } catch (_: Exception) {
    }
}
