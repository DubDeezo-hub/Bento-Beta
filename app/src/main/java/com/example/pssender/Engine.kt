package com.example.pssender

import org.apache.commons.net.DefaultSocketFactory
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.io.CopyStreamException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.time.Duration
import javax.net.ssl.SSLPeerUnverifiedException

// ---------------------------------------------------------------------------------------------
// The transfer engine: FTP uploads and downloads that survive a dropped connection.
//
// A transfer is tried again when the connection breaks after data has started to flow. Each new try asks the
// console how much of the file it already has and carries on from there (REST), so only the missing part is sent
// again. If the console or the source can't continue from a point, that try starts from the beginning instead.
// Nothing here touches the screen or the phone's storage: the app plugs those in through the small interfaces below.
// ---------------------------------------------------------------------------------------------

/** What the engine needs from the app around it. [TransferState] is the real one; the tests bring their own. */
interface TransferHooks {
    /** True once the user asked to stop. */
    val cancelled: Boolean

    /**
     * True while the user has paused. The transfer lets go of its connections where it is (the result says so, see
     * [TransferResult.paused]) and is run again from there once the pause is over.
     */
    val paused: Boolean get() = false

    /** The most bytes per second the transfer may move (0: no limit). Asked all the time, so a change takes effect at once. */
    val speedLimit: Long get() = 0L

    /** A try is about to move bytes, starting at byte [done] of [total] (-1 when the size is unknown). */
    fun starting(done: Long, total: Long)

    /** Bytes through so far, counted from the start of the file. */
    fun progress(done: Long, total: Long)

    /** A line about what is going on apart from moving bytes ("Reconnecting in 5 s…"). Empty text clears it. */
    fun status(text: String)

    /** Registers what closes the live connections, so a cancel can end a stalled transfer at once. Null removes it. */
    fun setAbort(hook: (() -> Unit)?)

    /** Waits [ms] milliseconds. Returns true as soon as the user cancels, without waiting any longer. */
    fun pause(ms: Long): Boolean {
        var left = ms
        while (left > 0L) {
            if (cancelled) return true
            val slice = if (left > 100L) 100L else left
            Thread.sleep(slice)
            left -= slice
        }
        return cancelled
    }
}

/** How a transfer ended: [ok] is true when it worked, and [message] is what to tell the user (in the app's language). */
data class TransferResult(
    val ok: Boolean,
    val message: String,
    /** True when it failed in a way that trying again later may fix (the network, not the settings). */
    val retry: Boolean = false,
    /** True when the user stopped it. */
    val cancelled: Boolean = false,
    /**
     * True when the user paused it. Nothing went wrong: whatever was moved so far is kept (see [ResumeMemory]), and
     * running the same transfer again continues from there. [message] is then what to tell if it is stopped instead.
     */
    val paused: Boolean = false
)

/** The result of a transfer that was paused. [message] is what to report if it is cancelled while it waits. */
fun pausedResult(message: String): TransferResult = TransferResult(false, message, retry = true, paused = true)

/**
 * True when a download that ended with [result] leaves nothing worth keeping: it was cancelled, or failed in a way that
 * trying again would not fix. A paused one, and one that gave up on a bad connection, keep what they have.
 */
fun leavesNothingToKeep(result: TransferResult): Boolean =
    !result.ok && !result.paused && (result.cancelled || !result.retry)

/** How patient the engine is. The defaults suit a console on the home network; the tests use shorter times. */
class TransferPolicy(
    /** How many failures in a row, without any progress in between, it takes to give up. */
    val maxFailures: Int = 10,
    /** Waits between tries: the first for the first failure, and so on. The last one repeats. */
    val delaysMs: List<Long> = listOf(3000L, 6000L, 12000L, 24000L, 30000L),
    /** A transfer that moves no data at all for this long counts as broken. */
    val stallMs: Long = 60_000L,
    /** How long to wait before asking the console for a file's size again, to see whether it is still growing. */
    val settleMs: Long = 1000L
) {
    fun delayFor(failuresInARow: Int): Long {
        if (delaysMs.isEmpty()) return 0L
        val i = (failuresInARow - 1).coerceIn(0, delaysMs.size - 1)
        return delaysMs[i]
    }
}

/**
 * What the engine remembers about one transfer between tries (and between "Try again" taps in the same session).
 * [started] means a partial copy may be waiting on the other side; [resumeAllowed] turns false once continuing
 * has gone wrong (the console refused it, or what it kept did not add up), after which every try starts from the
 * beginning, and a file that still comes out short is reported instead of tried again.
 */
class ResumeMemory {
    @Volatile
    var started = false

    @Volatile
    var resumeAllowed = true

    /** The place a download is written to, kept so that a later try can continue it. */
    @Volatile
    var sink: ResumableSink? = null

    /** What an upload reads from, kept when it learns things worth keeping (a web link remembers how long its file is). */
    @Volatile
    var source: ResumableSource? = null

    /** Cleans up when the transfer is given up for good (for example deletes the half-written download). */
    @Volatile
    var discard: (() -> Unit)? = null
}

// ---------------------------------------------------------------------------------------------
// Sources (what an upload reads) and sinks (what a download writes)
// ---------------------------------------------------------------------------------------------

/**
 * An open source of bytes. [total] is the length of the whole file (-1 when unknown). [startsAt] is the position of
 * the first byte [input] delivers: the position that was asked for, or 0 when the source could not start there.
 */
class OpenedSource(val input: InputStream, val total: Long, val startsAt: Long)

/** Something an upload can read from, and read again from the middle after a failure. */
interface ResumableSource {
    /** Opens the data so that the first byte is byte [offset] if possible, otherwise from byte 0 (see [OpenedSource]). */
    @Throws(IOException::class)
    fun open(offset: Long): OpenedSource

    /** Ends whatever is in progress right now (a cancel arrived). */
    fun abort() {}
}

/** Somewhere a download can be written to, and continued after a failure. */
interface ResumableSink {
    /** How many bytes an earlier try already stored. */
    fun storedLength(): Long

    /** Opens for writing so that the next byte lands at [offset]. Anything stored after [offset] is discarded. */
    @Throws(IOException::class)
    fun open(offset: Long): OutputStream
}

/**
 * Moves [input] forward by [offset] bytes: by seeking when it is a real file, otherwise by reading and dropping the
 * bytes. Returns false when the data ends before that point.
 */
fun skipTo(input: InputStream, offset: Long): Boolean {
    if (offset <= 0L) return true
    if (input is FileInputStream) {
        try {
            val channel = input.channel
            if (offset > channel.size()) return false
            channel.position(offset)
            return true
        } catch (_: IOException) {
            // a pipe or similar: it can't seek, so read through it below
        }
    }
    var left = offset
    val scrap = ByteArray(64 * 1024)
    while (left > 0L) {
        val skipped = input.skip(left)
        if (skipped > 0L) {
            left -= skipped
            continue
        }
        val n = input.read(scrap, 0, if (left < scrap.size) left.toInt() else scrap.size)
        if (n < 0) return false
        left -= n
    }
    return true
}

// ---------------------------------------------------------------------------------------------
// Errors: which are worth another try
// ---------------------------------------------------------------------------------------------

/** A failure whose [message] is fit to show as it is, and which says by itself whether another try can help. */
class TransferException(message: String, val retryable: Boolean) : IOException(message)

/** The error itself, without the wrapper that the FTP library puts around problems met while copying. */
fun rootError(e: Throwable): Throwable {
    var t = e
    var guard = 0
    while (t is CopyStreamException && t.ioException != null && guard < 8) {
        t = t.ioException
        guard++
    }
    return t
}

/** Everything in the chain of causes of [e], most outer first (and never an endless loop). */
private fun chainOf(e: Throwable): List<Throwable> {
    val out = ArrayList<Throwable>()
    var t: Throwable? = e
    while (t != null && out.size < 10) {
        out.add(t)
        t = t.cause
    }
    return out
}

/**
 * True when [e] looks like a hiccup of the connection (reset, timeout, dropped), which another try may get past.
 * False for problems that would only repeat: a file that isn't there, a certificate that isn't trusted, and the like.
 */
fun isTransientFailure(e: Throwable): Boolean {
    val chain = chainOf(e)
    for (t in chain) {
        if (t is TransferException) return t.retryable
    }
    for (t in chain) {
        if (t is FileNotFoundException || t is SSLPeerUnverifiedException || t is CertificateException ||
            t is SecurityException || t is IllegalArgumentException
        ) {
            return false
        }
    }
    return chain.any { it is IOException }
}

/** What to tell the user about [e]. */
fun describeFailure(e: Throwable): String {
    for (t in chainOf(e)) {
        if (t is TransferException) return t.message ?: AppText.get(R.string.err_failed, t.javaClass.simpleName)
    }
    val root = rootError(e)
    return Hints.tag(AppText.get(R.string.err_failed, root.message ?: root.javaClass.simpleName), topicFor(e))
}

/** HTTP answers that say "try again later". */
fun isTransientHttp(code: Int): Boolean =
    code == 408 || code == 425 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504 ||
        (code in 520..524)

/** A problem to report: [message] for the user, and whether [retry]ing could help. */
internal class Problem(val message: String, val retry: Boolean)

// ---------------------------------------------------------------------------------------------
// Progress counting and the stall watchdog
// ---------------------------------------------------------------------------------------------

/**
 * Notices a transfer that has stopped moving without the connection ever reporting an error (a console that hangs,
 * Wi-Fi that vanished). After [limitMs] without any data it runs [onStall], which closes the connections; the
 * blocked read or write then fails and the transfer is tried again. It only watches between [arm] and [disarm].
 */
internal class StallWatchdog(private val limitMs: Long, private val onStall: () -> Unit) {
    @Volatile
    private var last = System.nanoTime()

    @Volatile
    private var armed = false

    @Volatile
    private var closed = false

    @Volatile
    var fired = false
        private set

    private val thread = Thread({ watch() }, "stall-watchdog").also { it.isDaemon = true }

    fun start() {
        thread.start()
    }

    fun arm() {
        last = System.nanoTime()
        armed = true
    }

    fun disarm() {
        armed = false
    }

    fun touch() {
        last = System.nanoTime()
    }

    fun close() {
        closed = true
        thread.interrupt()
    }

    private fun watch() {
        val every = (limitMs / 4).coerceIn(20L, 1000L)
        try {
            while (!closed) {
                Thread.sleep(every)
                if (armed && !closed && (System.nanoTime() - last) / 1_000_000L > limitMs) {
                    fired = true
                    onStall()
                    return
                }
            }
        } catch (_: InterruptedException) {
        }
    }
}

/** Counts bytes, reports progress at most every 250 ms, feeds the watchdog, and aborts the transfer once a cancel was requested. */
internal class ProgressMeter(
    private val total: Long,
    start: Long,
    private val hooks: TransferHooks,
    private val watchdog: StallWatchdog?
) {
    /** Bytes through, counted from the start of the file. */
    var done = start
        private set

    /** Bytes through in this try only. */
    var moved = 0L
        private set

    private var lastReport = 0L
    private val pacer = Pacer()

    init {
        hooks.starting(start, total)
    }

    /** Ends the transfer (with an exception the engine knows) when the user stopped or paused it. */
    private fun stopIfAsked() {
        if (hooks.cancelled) throw IOException("Cancelled")
        if (hooks.paused) throw IOException("Paused")
    }

    fun add(n: Long) {
        stopIfAsked()
        done += n
        moved += n
        watchdog?.touch()
        val now = System.nanoTime()
        if (now - lastReport > 250_000_000L) {
            lastReport = now
            hooks.progress(done, total)
        }
        slowDown(n)
    }

    /** With a speed limit: waits as long as it takes to stay under it, and keeps watching for a stop meanwhile. */
    private fun slowDown(n: Long) {
        var wait = pacer.waitMs(n, hooks.speedLimit)
        while (wait > 0L) {
            val slice = if (wait > 100L) 100L else wait
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                throw IOException("Cancelled")
            }
            wait -= slice
            watchdog?.touch() // waiting on purpose is not a stall
            stopIfAsked()
        }
    }

    /** How much of the [len] bytes on offer to take in one go: all of it, or a smaller piece under a speed limit (see [pieceFor]). */
    fun pieceSize(len: Int): Int = pieceFor(hooks.speedLimit, len)

    /** Reports the count right away (the regular reports are rate-limited). */
    fun report() = hooks.progress(done, total)
}

/** Wraps a stream being uploaded so progress is reported and a cancel request stops the transfer. */
internal class CountingInputStream(inner: InputStream, private val meter: ProgressMeter) : FilterInputStream(inner) {
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) meter.add(1L)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, meter.pieceSize(len))
        if (n > 0) meter.add(n.toLong())
        return n
    }
}

/** Wraps the stream a download is written to so progress is reported and a cancel request stops the transfer. */
internal class CountingOutputStream(inner: OutputStream, private val meter: ProgressMeter) : FilterOutputStream(inner) {
    override fun write(b: Int) {
        out.write(b)
        meter.add(1L)
    }

    // FilterOutputStream would otherwise write one byte at a time. Under a speed limit the data goes in smaller pieces
    // with a wait after each; without one it is a single write, as always.
    override fun write(b: ByteArray, off: Int, len: Int) {
        var pos = off
        var left = len
        while (left > 0) {
            val piece = meter.pieceSize(left)
            out.write(b, pos, piece)
            meter.add(piece.toLong())
            pos += piece
            left -= piece
        }
    }
}

// ---------------------------------------------------------------------------------------------
// FTP plumbing
// ---------------------------------------------------------------------------------------------

/**
 * Remembers every socket the FTP client opens (the login connection and each data connection), so a cancel can
 * close them all. Closing only the login connection would leave a transfer that is stuck in a blocked read or
 * write hanging.
 */
internal class TrackingSocketFactory : DefaultSocketFactory() {
    private val sockets = mutableListOf<Socket>()

    private fun track(s: Socket): Socket {
        synchronized(sockets) { sockets.add(s) }
        return s
    }

    override fun createSocket(): Socket = track(super.createSocket())
    override fun createSocket(host: String, port: Int): Socket = track(super.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        track(super.createSocket(host, port, localAddr, localPort))

    override fun createSocket(address: InetAddress, port: Int): Socket = track(super.createSocket(address, port))
    override fun createSocket(address: InetAddress, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        track(super.createSocket(address, port, localAddr, localPort))

    fun closeAll() {
        val all = synchronized(sockets) { sockets.toList() }
        for (s in all) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }
}

/** One FTP client whose connections can all be shut at once. */
internal class FtpSession {
    private val factory = TrackingSocketFactory()
    val ftp = FTPClient().also {
        it.setSocketFactory(factory)
        // A console that stops answering must not leave a transfer waiting forever
        it.setDefaultTimeout(60_000)
        it.setDataTimeout(Duration.ofSeconds(60))
        // Larger chunks than the library's 1 KB default: far fewer calls, so a fast network is not held back
        it.setBufferSize(256 * 1024)
    }

    /** Closes every connection, which also frees a thread stuck waiting on a stalled network. Safe to call twice. */
    fun abort() {
        factory.closeAll()
        try {
            ftp.disconnect()
        } catch (_: Exception) {
        }
    }
}

/** Connects and logs in. Returns null when ready, otherwise the reason it isn't. */
internal fun ftpLogin(ftp: FTPClient, s: FtpSettings): Problem? {
    ftp.setConnectTimeout(10_000)
    ftp.connect(s.host, s.port)
    if (!ftp.login(s.user, s.pass)) {
        return Problem(Hints.tag(AppText.get(R.string.err_login), HelpTopic.FTP_LOGIN), FTPReply.isNegativeTransient(ftp.replyCode))
    }
    ftp.enterLocalPassiveMode()
    ftp.setFileType(FTP.BINARY_FILE_TYPE)
    return null
}

/** Connects and logs in. Returns null when ready, otherwise the reason it isn't. */
internal fun ftpConnect(ftp: FTPClient, s: FtpSettings): String? = ftpLogin(ftp, s)?.message

/** Size of [remote] on the server, or -1 when the server can't tell (or the file isn't there). */
internal fun remoteSizeOf(ftp: FTPClient, remote: String): Long {
    val text = ftp.getSize(remote) ?: return -1L
    return text.trim().split(' ').firstOrNull()?.toLongOrNull() ?: -1L
}

/**
 * Size of [remote] once it has stopped changing: a connection that just broke may still be writing the last of its
 * data on the console's side for a moment. Returns -1 when the size is unknown, the file is empty or missing, it keeps
 * growing, or the user cancels.
 */
internal fun settledSizeOf(ftp: FTPClient, remote: String, hooks: TransferHooks, settleMs: Long): Long {
    var last = remoteSizeOf(ftp, remote)
    var rounds = 0
    while (last > 0L && rounds < 5) {
        if (hooks.pause(settleMs)) return -1L
        val again = remoteSizeOf(ftp, remote)
        if (again == last) return last
        last = again
        rounds++
    }
    return -1L
}

/**
 * Runs [work] with a fresh FTP session and watchdog and turns the outcome into a [TransferResult]. [work] returns null
 * when it worked, otherwise the problem. Registers an abort hook so a cancel closes the connections immediately, even
 * when the network has stalled.
 */
private fun runFtpAttempt(
    hooks: TransferHooks,
    policy: TransferPolicy,
    okMessage: String,
    cancelMessage: String,
    abortSource: () -> Unit,
    work: (FtpSession, StallWatchdog) -> Problem?
): TransferResult {
    val session = FtpSession()
    val abortAll = {
        abortSource()
        session.abort()
    }
    val watchdog = StallWatchdog(policy.stallMs, abortAll)
    watchdog.start()
    hooks.setAbort(abortAll)
    return try {
        val problem = work(session, watchdog)
        when {
            problem == null -> TransferResult(true, okMessage)
            hooks.cancelled -> TransferResult(false, cancelMessage, cancelled = true)
            hooks.paused -> pausedResult(cancelMessage)
            else -> TransferResult(false, problem.message, retry = problem.retry)
        }
    } catch (e: Exception) {
        when {
            hooks.cancelled -> TransferResult(false, cancelMessage, cancelled = true)
            hooks.paused -> pausedResult(cancelMessage)
            else -> TransferResult(false, describeFailure(e), retry = isTransientFailure(e))
        }
    } finally {
        watchdog.disarm()
        watchdog.close()
        // Say goodbye politely, but never wait long for a console that has stopped answering
        try {
            if (session.ftp.isConnected) {
                session.ftp.setSoTimeout(5000)
                session.ftp.logout()
            }
        } catch (_: Exception) {
        }
        session.abort()
        hooks.setAbort(null)
    }
}

// ---------------------------------------------------------------------------------------------
// The retry loop
// ---------------------------------------------------------------------------------------------

/**
 * Runs [attempt] again after each failure that looks like a dropped connection, waiting a little longer each time,
 * until it works, the user cancels, the failure is one that would only repeat, or too many tries in a row got nowhere.
 * [attempt] gets true as its second argument when an earlier try already moved data (so it should continue, not
 * start over). A failure before any data moved is reported at once: wrong address, wrong login and a dead link
 * should not make the user wait.
 *
 * A try got somewhere when it read further into the file than any try before, or when it began further on than the
 * try before it (what the console or the phone holds has grown). Only tries that got nowhere add up towards giving up.
 */
internal fun runWithRetries(
    hooks: TransferHooks,
    policy: TransferPolicy,
    mem: ResumeMemory,
    cancelMessage: String,
    attempt: (TransferHooks, Boolean) -> TransferResult
): TransferResult {
    var furthest = 0L
    var thisStart = -1L
    val watching = object : TransferHooks by hooks {
        override fun starting(done: Long, total: Long) {
            thisStart = done
            hooks.starting(done, total)
        }

        override fun progress(done: Long, total: Long) {
            if (done > furthest) furthest = done
            hooks.progress(done, total)
        }
    }
    var failures = 0
    var lastFurthest = 0L
    var lastStart = -1L
    var tries = 0
    while (true) {
        tries++
        thisStart = -1L
        val result = attempt(watching, tries > 1 || mem.started)
        if (result.ok) return result
        if (furthest > 0L) mem.started = true
        if (result.cancelled || hooks.cancelled) return TransferResult(false, cancelMessage, cancelled = true)
        // Paused by the user: not a failure. The caller waits for the pause to end and runs the transfer again, which
        // continues from the point it reached (mem.started says data is waiting on the other side)
        if (result.paused) return result
        if (!result.retry || furthest <= 0L) return result
        val gotFurther = furthest > lastFurthest
        val beganFurther = thisStart >= 0L && lastStart >= 0L && thisStart > lastStart
        if (gotFurther || beganFurther) failures = 0
        lastFurthest = furthest
        if (thisStart >= 0L) lastStart = thisStart
        failures++
        if (failures > policy.maxFailures) {
            return TransferResult(false, Hints.tag(AppText.get(R.string.err_gave_up, result.message), HelpTopic.DROPPED), retry = true)
        }
        var left = policy.delayFor(failures)
        while (left > 0L) {
            if (hooks.paused) return pausedResult(cancelMessage) // the wait for the next try is not worth keeping up
            hooks.status(AppText.get(R.string.transfer_retry_in, ((left + 999L) / 1000L).toInt()))
            val slice = if (left > 1000L) 1000L else left
            if (hooks.pause(slice)) return TransferResult(false, cancelMessage, cancelled = true)
            left -= slice
        }
        hooks.status(AppText.get(R.string.transfer_reconnecting))
    }
}

// ---------------------------------------------------------------------------------------------
// Uploads
// ---------------------------------------------------------------------------------------------

private sealed interface Pass {
    object Done : Pass
    object FromStart : Pass
    class Failed(val problem: Problem) : Pass
}

/** One go at storing the data of [source] from [wanted] on as [remote]. */
private fun storePass(
    ftp: FTPClient, watchdog: StallWatchdog, source: ResumableSource, remote: String,
    wanted: Long, mayRetryShort: Boolean, hooks: TransferHooks
): Pass {
    var opened = source.open(wanted)
    try {
        var start = opened.startsAt
        if (wanted > 0L && start == wanted && opened.total >= 0L) {
            if (wanted == opened.total) {
                // Everything is there already (the last try got the whole file across before the connection broke)
                hooks.starting(wanted, wanted)
                hooks.progress(wanted, wanted)
                return Pass.Done
            }
            if (wanted > opened.total) {
                // The copy on the console is longer than the file: it can't be the beginning of it. Start over
                opened.input.close()
                opened = source.open(0L)
                start = opened.startsAt
            }
        }
        val meter = ProgressMeter(opened.total, start, hooks, watchdog)
        val counted = CountingInputStream(opened.input, meter)
        if (start > 0L) ftp.setRestartOffset(start)
        watchdog.arm()
        val ok = ftp.storeFile(remote, counted)
        watchdog.disarm()
        meter.report()
        if (!ok) {
            // 500-504: the console doesn't know how to continue a file. Send it from the start instead
            if (start > 0L && ftp.replyCode in 500..504) return Pass.FromStart
            return Pass.Failed(
                Problem(
                    Hints.tag(AppText.get(R.string.err_upload, ftp.replyString.trim()), topicForFtpReply(ftp.replyCode)),
                    FTPReply.isNegativeTransient(ftp.replyCode)
                )
            )
        }
        // The console says it has the file. Check that it holds what was sent: a console may report success for a
        // connection that broke or a disk that filled up, and one that ignored the restart point has a wrong file
        val sent = start + meter.moved
        val actual = remoteSizeOf(ftp, remote)
        if (actual >= 0L && actual != sent) {
            val kept = Problem(
                Hints.tag(AppText.get(R.string.err_short_upload, fmtBytes(actual), fmtBytes(sent)), HelpTopic.CONSOLE_STORAGE),
                false
            )
            if (start > 0L) {
                // Nothing was added to the copy the console had: it isn't storing anything (a full disk?), and sending
                // it all again would not help. Otherwise it may have ignored the restart point: send everything again
                return if (actual == start) Pass.Failed(kept) else Pass.FromStart
            }
            if (actual < sent) return Pass.Failed(Problem(kept.message, mayRetryShort))
        }
        return Pass.Done
    } finally {
        try {
            opened.input.close()
        } catch (_: Exception) {
        }
    }
}

private fun storeAttempt(
    session: FtpSession, watchdog: StallWatchdog, source: ResumableSource, remote: String, s: FtpSettings,
    resume: Boolean, mem: ResumeMemory, hooks: TransferHooks, policy: TransferPolicy
): Problem? {
    val ftp = session.ftp
    ftpLogin(ftp, s)?.let { return it }
    var startOver = !(resume && mem.resumeAllowed)
    while (true) {
        var wanted = 0L
        if (!startOver) {
            wanted = settledSizeOf(ftp, remote, hooks, policy.settleMs)
            if (hooks.cancelled) throw IOException("Cancelled")
            if (wanted < 0L) wanted = 0L
        }
        when (val pass = storePass(ftp, watchdog, source, remote, wanted, mem.resumeAllowed, hooks)) {
            is Pass.Done -> return null
            is Pass.Failed -> return pass.problem
            is Pass.FromStart -> {
                // Continuing didn't work with this console or source: no more tries to continue, start over now
                mem.resumeAllowed = false
                startOver = true
            }
        }
    }
}

/**
 * Uploads what [source] delivers to [remoteDir]/[remoteName] on the console's FTP server, and carries on after a
 * dropped connection.
 */
fun ftpUpload(
    source: ResumableSource, remoteName: String, s: FtpSettings, remoteDir: String,
    mem: ResumeMemory, hooks: TransferHooks, policy: TransferPolicy = TransferPolicy()
): TransferResult {
    val remote = remoteDir.trimEnd('/') + "/" + remoteName
    val okMessage = AppText.get(R.string.sent_to_ps5, remote, s.model)
    val cancelMessage = AppText.get(R.string.err_cancelled_upload, remote)
    return runWithRetries(hooks, policy, mem, cancelMessage) { h, resume ->
        runFtpAttempt(h, policy, okMessage, cancelMessage, { source.abort() }) { session, watchdog ->
            storeAttempt(session, watchdog, source, remote, s, resume, mem, h, policy)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Downloads
// ---------------------------------------------------------------------------------------------

private fun retrieveAttempt(
    session: FtpSession, watchdog: StallWatchdog, sink: ResumableSink, remote: String, size: Long, s: FtpSettings,
    resume: Boolean, mem: ResumeMemory, hooks: TransferHooks
): Problem? {
    val ftp = session.ftp
    ftpLogin(ftp, s)?.let { return it }
    var startOver = !(resume && mem.resumeAllowed)
    while (true) {
        var offset = if (startOver) 0L else sink.storedLength()
        var expected = size
        if (offset > 0L) {
            if (expected < 0L) expected = remoteSizeOf(ftp, remote)
            if (expected < 0L || offset > expected) {
                offset = 0L // can't tell what the file should end up as, or what is stored is too long: start over
            } else if (offset == expected) {
                hooks.starting(offset, expected)
                hooks.progress(offset, expected)
                return null // everything is stored already
            }
        }
        val out = sink.open(offset)
        var ok = false
        try {
            val meter = ProgressMeter(expected, offset, hooks, watchdog)
            val counted = CountingOutputStream(out, meter)
            if (offset > 0L) ftp.setRestartOffset(offset)
            watchdog.arm()
            ok = ftp.retrieveFile(remote, counted)
            watchdog.disarm()
            meter.report()
        } finally {
            try {
                out.close()
            } catch (_: Exception) {
            }
        }
        if (!ok) {
            if (offset > 0L && ftp.replyCode in 500..504) {
                mem.resumeAllowed = false
                startOver = true
                continue // the console doesn't know how to continue a file: fetch it from the start
            }
            return Problem(
                Hints.tag(AppText.get(R.string.err_download, ftp.replyString.trim()), topicForFtpReply(ftp.replyCode)),
                FTPReply.isNegativeTransient(ftp.replyCode)
            )
        }
        val stored = sink.storedLength()
        if (offset > 0L && expected >= 0L && stored != expected) {
            mem.resumeAllowed = false
            startOver = true
            continue // the continued download doesn't add up: fetch it again from the start
        }
        if (offset == 0L && expected >= 0L && stored < expected) {
            // Less than the folder listing said. The listing may be out of date, so the console is asked once more
            val actual = remoteSizeOf(ftp, remote)
            if (actual < 0L || actual != stored) {
                // The connection broke or the phone's storage stopped taking data, without anything reporting an error
                return Problem(
                    Hints.tag(AppText.get(R.string.err_short_download, fmtBytes(stored), fmtBytes(expected)), HelpTopic.DROPPED),
                    mem.resumeAllowed
                )
            }
        }
        return null
    }
}

/**
 * Downloads [remote] from the console into [sink], and carries on after a dropped connection. [size] is the size from
 * the folder listing (-1 when unknown). [okMessage] is what to tell the user when it worked.
 */
fun ftpDownload(
    sink: ResumableSink, remote: String, size: Long, s: FtpSettings, okMessage: String,
    mem: ResumeMemory, hooks: TransferHooks, policy: TransferPolicy = TransferPolicy()
): TransferResult {
    val cancelMessage = AppText.get(R.string.err_cancelled_download)
    return runWithRetries(hooks, policy, mem, cancelMessage) { h, resume ->
        runFtpAttempt(h, policy, okMessage, cancelMessage, {}) { session, watchdog ->
            retrieveAttempt(session, watchdog, sink, remote, size, s, resume, mem, h)
        }
    }
}
