package com.example.pssender

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

// ---------------------------------------------------------------------------------------------
// Fast folder upload: with Crimson's own unpack payload running on the console (payload-src/unpack, port 9046), a whole
// folder goes over one connection, folder after folder and file after file, and the payload writes them as they come.
// Over FTP each file costs a few round trips; here there are none between files. See unpack.c for the protocol.
// ---------------------------------------------------------------------------------------------

/** The unpack payload's port. */
const val UNPACK_PORT = 9046

/** What the payload says to a PING, and the first line of a folder. */
const val UNPACK_HELLO = "CRIMSON-UNPACK 1"

/** The payload as it is inside the app (built from payload-src/unpack by the build). */
const val UNPACK_ASSET = "crimson-unpack.elf"

/** One file of a folder stream: its place in the folder, its size, and how to read it. */
class StreamFile(val rel: String, val size: Long, val open: () -> InputStream)

/** True when the unpack payload answers on [host]. Blocking; quick when nothing listens. */
fun unpackerAnswers(host: String, port: Int = UNPACK_PORT, timeoutMs: Int = 1500): Boolean =
    try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply { write("PING\n".toByteArray()); flush() }
            readLine(s.getInputStream()) == UNPACK_HELLO
        }
    } catch (_: Exception) {
        false
    }

/** The bytes that start one record ('D' or 'F'): kind, path length, path, and for a file its size. */
fun streamRecordHead(kind: Char, rel: String, size: Long = 0L): ByteArray {
    val path = rel.toByteArray(Charsets.UTF_8)
    require(path.isNotEmpty() && path.size <= 1024) { "path length" }
    val out = java.io.ByteArrayOutputStream(path.size + 11)
    out.write(kind.code)
    out.write(path.size ushr 8)
    out.write(path.size and 0xFF)
    out.write(path)
    if (kind == 'F') for (shift in 56 downTo 0 step 8) out.write(((size ushr shift) and 0xFF).toInt())
    return out.toByteArray()
}

/**
 * Writes the folder stream for [dest] to [out]: the [folders] first (so empty ones are made too), then the [files].
 * [moved] is told every piece that went. A file that gives fewer bytes than its size is an error (the payload would
 * otherwise read the next file's start as its end).
 */
fun writeFolderStream(out: OutputStream, dest: String, folders: List<String>, files: List<StreamFile>, moved: (Long) -> Unit) {
    out.write("$UNPACK_HELLO\nDEST $dest\n".toByteArray(Charsets.UTF_8))
    for (f in folders.sortedBy { it.count { c -> c == '/' } }) out.write(streamRecordHead('D', f))
    val buf = ByteArray(1024 * 1024)
    for (f in files) {
        out.write(streamRecordHead('F', f.rel, f.size))
        var left = f.size
        f.open().use { input ->
            while (left > 0L) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("${f.rel} is shorter than its size")
                out.write(buf, 0, n)
                left -= n
                moved(n.toLong())
            }
        }
    }
    out.write('E'.code)
    out.flush()
}

/** The payload's answer: null when it took everything, otherwise what it said went wrong. */
fun streamAnswerProblem(answer: String?): String? = when {
    answer == null -> AppText.get(R.string.unpack_no_answer)
    answer.startsWith("OK ") -> null
    answer.startsWith("ERR ") -> answer.removePrefix("ERR ")
    else -> answer
}

/**
 * Sends [files] and [folders] to [dest] on the console at [host] through the unpack payload, reporting progress to
 * [hooks]. Blocking.
 */
fun sendFolderStream(host: String, dest: String, folders: List<String>, files: List<StreamFile>, hooks: TransferHooks): TransferResult {
    val total = files.sumOf { it.size }
    val done = AppText.get(R.string.unpack_done, files.size, dest)
    val cancelled = AppText.get(R.string.err_cancelled_upload, dest)
    hooks.starting(0L, total)
    var sent = 0L
    var lastReport = 0L
    val socket = Socket()
    hooks.setAbort { try { socket.close() } catch (_: Exception) {} }
    return try {
        socket.connect(InetSocketAddress(host, UNPACK_PORT), 10_000)
        socket.sendBufferSize = 1024 * 1024
        socket.soTimeout = 60_000
        val out = BufferedOutputStream(socket.getOutputStream(), 1024 * 1024)
        writeFolderStream(out, dest, folders, files) { n ->
            if (hooks.cancelled || hooks.paused) throw IOException("Cancelled")
            sent += n
            val now = System.nanoTime()
            if (now - lastReport > 250_000_000L) {
                lastReport = now
                hooks.progress(sent, total)
            }
        }
        hooks.progress(sent, total)
        val problem = streamAnswerProblem(readLine(socket.getInputStream()))
        if (problem == null) TransferResult(true, done) else TransferResult(false, AppText.get(R.string.unpack_failed, problem))
    } catch (e: Exception) {
        when {
            hooks.cancelled -> TransferResult(false, cancelled, cancelled = true)
            hooks.paused -> pausedResult(cancelled)
            else -> TransferResult(false, AppText.get(R.string.unpack_failed, describeFailure(e)), retry = isTransientFailure(e))
        }
    } finally {
        hooks.setAbort(null)
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (sb.length < 512) {
        val b = input.read()
        if (b < 0) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) return sb.toString()
        sb.append(b.toChar())
    }
    return sb.toString()
}
