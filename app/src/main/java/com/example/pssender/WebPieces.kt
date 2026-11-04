package com.example.pssender

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// ---------------------------------------------------------------------------------------------
// Faster web downloads: many file hosts slow down each connection, so a file is fetched as several pieces at the same
// time (Range requests) and handed on in order, as one stream, to whatever reads it (the console's FTP upload). Memory
// stays small: only a few pieces are held ahead of the reader. A piece that fails is asked for again; a server that stops
// honoring ranges ends the stream with an error, and the next try of the transfer falls back to one connection.
// ---------------------------------------------------------------------------------------------

/** How big one piece is. */
const val WEB_PIECE_SIZE = 4 * 1024 * 1024

/** How many pieces are fetched at the same time. */
const val WEB_PIECE_LANES = 4

/** How many pieces may wait ahead of the reader (with the ones being fetched, this bounds the memory used). */
const val WEB_PIECE_AHEAD = WEB_PIECE_LANES + 2

/** How often one piece is asked for before the stream gives up (the transfer then continues from where it got to). */
const val WEB_PIECE_TRIES = 3

/** The inclusive byte range of piece [index] of a file of [total] bytes whose pieces start at [from]. */
fun pieceRange(from: Long, index: Long, total: Long, size: Int = WEB_PIECE_SIZE): LongRange? {
    val start = from + index * size
    if (start >= total) return null
    return start..minOf(total, start + size) - 1
}

/** Raised when the server answered a piece with the whole file or another file: ranges can't be used for it any more. */
class RangesRefused(message: String) : IOException(message)

/**
 * The bytes of a web file from [from] to its end ([total] bytes long), fetched as pieces by [lanes] threads. [fetchPiece]
 * asks for one range and returns its bytes (it throws [RangesRefused] when the answer isn't that range). [first] are the
 * bytes already fetched from [from] on (they are handed out before anything else).
 */
class PieceStream(
    from: Long,
    private val total: Long,
    first: ByteArray,
    private val lanes: Int = WEB_PIECE_LANES,
    private val size: Int = WEB_PIECE_SIZE,
    private val fetchPiece: (LongRange) -> ByteArray
) : InputStream() {
    private val lock = java.lang.Object()
    private val ready = HashMap<Long, ByteArray>()
    private val base = from + first.size
    private val pieces = if (base >= total) 0L else (total - base + size - 1) / size
    private var nextToFetch = 0L
    private var nextToRead = 0L
    private var failure: IOException? = null
    private val stopped = AtomicBoolean(false)
    private var current: ByteArray = first
    private var pos = 0

    init {
        repeat(minOf(lanes.toLong(), pieces).toInt()) { i -> thread(name = "web-piece-$i", isDaemon = true) { work() } }
    }

    private fun work() {
        while (!stopped.get()) {
            val index: Long
            synchronized(lock) {
                // Don't run too far ahead of the reader
                while (!stopped.get() && nextToFetch < pieces && nextToFetch - nextToRead >= WEB_PIECE_AHEAD) lock.wait(200)
                if (stopped.get() || nextToFetch >= pieces) return
                index = nextToFetch++
            }
            val range = pieceRange(base, index, total, size) ?: return
            var bytes: ByteArray? = null
            var last: IOException? = null
            for (attempt in 1..WEB_PIECE_TRIES) {
                if (stopped.get()) return
                try {
                    val got = fetchPiece(range)
                    if (got.size.toLong() != range.last - range.first + 1) throw IOException("short piece")
                    bytes = got
                    break
                } catch (e: RangesRefused) {
                    last = e
                    break
                } catch (e: IOException) {
                    last = e
                }
            }
            synchronized(lock) {
                if (bytes != null) ready[index] = bytes else if (failure == null) failure = last ?: IOException("piece failed")
                lock.notifyAll()
            }
            if (bytes == null) return
        }
    }

    /** Moves on to the next piece, waiting for it. False at the end of the file. */
    private fun advance(): Boolean {
        synchronized(lock) {
            while (true) {
                if (nextToRead >= pieces) return false
                ready.remove(nextToRead)?.let {
                    nextToRead++
                    lock.notifyAll()
                    current = it
                    pos = 0
                    return true
                }
                failure?.let { throw it }
                if (stopped.get()) throw IOException("stopped")
                lock.wait(200)
            }
        }
    }

    override fun read(): Int {
        if (pos >= current.size && !advance()) return -1
        return current[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= current.size && !advance()) return -1
        val n = minOf(len, current.size - pos)
        System.arraycopy(current, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = current.size - pos

    override fun close() {
        stopped.set(true)
        synchronized(lock) {
            ready.clear()
            lock.notifyAll()
        }
    }
}

/** Reads [input] to its end into one array of at most [max] bytes; more than that is an error. */
internal fun readAll(input: InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(max, 1 shl 20))
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (out.size() + n > max) throw IOException("piece too long")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
