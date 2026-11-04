package com.example.pssender

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FasterTransfersTest {
    private fun data(n: Int) = ByteArray(n) { i -> ((i * 7 + (i ushr 9)) and 0xFF).toByte() }

    /** A web server for one file: answers ranges when [ranges] is on, the whole file otherwise. */
    private class FakeServer(val file: ByteArray, val ranges: Boolean = true, val etag: String = "\"v1\"") {
        var requests = 0
        fun fetcher() = object : HttpFetcher {
            override fun get(url: String, headers: Map<String, String>): HttpReply {
                synchronized(this@FakeServer) { requests++ }
                val range = headers["Range"]
                val head = mapOf("ETag" to etag)
                if (!ranges || range == null) {
                    return HttpReply(200, { head[it] }, file.size.toLong(), file.inputStream(), {})
                }
                val m = Regex("""bytes=(\d+)-(\d*)""").find(range)!!
                val a = m.groupValues[1].toLong()
                val b = m.groupValues[2].toLongOrNull()?.coerceAtMost(file.size - 1L) ?: (file.size - 1L)
                val part = file.copyOfRange(a.toInt(), b.toInt() + 1)
                val all = head + ("Content-Range" to "bytes $a-$b/${file.size}")
                return HttpReply(206, { all[it] }, part.size.toLong(), part.inputStream(), {})
            }
            override fun abort() {}
        }
    }

    @Test
    fun aWebFileComesInPiecesAndInOrder() {
        val file = data(WEB_PIECE_SIZE * 3 + 12345)
        val server = FakeServer(file)
        val source = HttpSource("https://x/f", "ua", null, server.fetcher(), pieceFetchers = { server.fetcher() })
        val opened = source.open(0L)
        assertEquals(file.size.toLong(), opened.total)
        assertArrayEquals(file, opened.input.readBytes())
        assertTrue(server.requests >= 4) // the first piece, then the others each on their own
        assertFalse(source.piecesOff)
    }

    @Test
    fun aBreakContinuesFromTheMiddleInPieces() {
        val file = data(WEB_PIECE_SIZE * 2 + 99)
        val server = FakeServer(file)
        val source = HttpSource("https://x/f", "ua", null, server.fetcher(), pieceFetchers = { server.fetcher() })
        source.open(0L).input.close()
        val from = WEB_PIECE_SIZE + 500L
        val again = source.open(from)
        assertEquals(from, again.startsAt)
        assertArrayEquals(file.copyOfRange(from.toInt(), file.size), again.input.readBytes())
    }

    @Test
    fun aServerWithoutRangesGetsOneConnection() {
        val file = data(WEB_PIECE_SIZE + 10)
        val server = FakeServer(file, ranges = false)
        val source = HttpSource("https://x/f", "ua", null, server.fetcher(), pieceFetchers = { server.fetcher() })
        val opened = source.open(0L)
        assertArrayEquals(file, opened.input.readBytes())
        assertEquals(1, server.requests)
    }

    @Test
    fun aSmallFileIsOnePiece() {
        val file = data(1000)
        val server = FakeServer(file)
        val source = HttpSource("https://x/f", "ua", null, server.fetcher(), pieceFetchers = { server.fetcher() })
        assertArrayEquals(file, source.open(0L).input.readBytes())
        assertEquals(1, server.requests)
    }

    @Test
    fun aFailingPieceEndsTheStreamWithAnError() {
        val file = data(WEB_PIECE_SIZE * 2)
        var calls = 0
        val stream = PieceStream(0L, file.size.toLong(), file.copyOfRange(0, WEB_PIECE_SIZE)) { _ ->
            calls++
            throw IOException("network down")
        }
        val buf = ByteArray(WEB_PIECE_SIZE)
        var read = 0
        while (read < WEB_PIECE_SIZE) read += stream.read(buf, read, WEB_PIECE_SIZE - read)
        try {
            stream.read()
            throw AssertionError("expected an error")
        } catch (_: IOException) {
        }
        assertEquals(WEB_PIECE_TRIES, calls)
        stream.close()
    }

    @Test
    fun piecesAndPartsCoverTheFileOnce() {
        assertEquals(0L..9L, pieceRange(0L, 0L, 25L, 10))
        assertEquals(20L..24L, pieceRange(0L, 2L, 25L, 10))
        assertNull(pieceRange(0L, 3L, 25L, 10))
        val parts = splitBounds(1003L, 4)
        assertEquals(4, parts.size)
        assertEquals(1003L, parts.sumOf { it.last - it.first + 1 })
        assertEquals(0L, parts.first().first)
        assertEquals(1002L, parts.last().last)
        for (i in 1 until parts.size) assertEquals(parts[i - 1].last + 1, parts[i].first)
        assertEquals(1, splitBounds(10L, 1).size)
    }

    @Test
    fun theSplitTestFileShowsAPartInTheWrongPlace() {
        val bytes = splitTestBytes(1 shl 20)
        val part = bytes.size / 4
        // Every quarter differs from the one before it, so swapped parts can't compare equal
        assertFalse(bytes.copyOfRange(0, part).contentEquals(bytes.copyOfRange(part, 2 * part)))
        val src = BytesSource(bytes)
        assertEquals(bytes.size.toLong(), src.size())
        assertEquals(part.toLong(), src.open(part.toLong()).startsAt)
    }

    @Test
    fun splittingIsOnlyForTheTestedConsole() {
        val s = FtpSettings("10.0.0.5", 2121, "anonymous", "")
        TransferTuning.splitOkFor = ""
        assertEquals(1, TransferTuning.partsFor(s))
        TransferTuning.splitOkFor = TransferTuning.consoleKey(s)
        assertEquals(SPLIT_PARTS, TransferTuning.partsFor(s))
        assertEquals(1, TransferTuning.partsFor(s.copy(port = 1337)))
        TransferTuning.splitOkFor = ""
    }
}
