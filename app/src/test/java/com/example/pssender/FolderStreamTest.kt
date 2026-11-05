package com.example.pssender

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

class FolderStreamTest {
    @Test
    fun recordsAreWhatThePayloadReads() {
        assertArrayEquals(byteArrayOf('D'.code.toByte(), 0, 3, 'a'.code.toByte(), '/'.code.toByte(), 'b'.code.toByte()), streamRecordHead('D', "a/b"))
        val f = streamRecordHead('F', "x", 0x0102030405L)
        assertArrayEquals(byteArrayOf('F'.code.toByte(), 0, 1, 'x'.code.toByte(), 0, 0, 0, 1, 2, 3, 4, 5), f)
        assertNull(streamAnswerProblem("OK 3 100"))
        assertEquals("open x: denied", streamAnswerProblem("ERR open x: denied"))
    }

    /** The app's own stream, through the real payload (its Linux build, made by the build before the tests). */
    @Test
    fun aFolderGoesThroughThePayloadUnchanged() {
        val binary = listOf(File("../payload-src/unpack/unpack-linux"), File("payload-src/unpack/unpack-linux")).firstOrNull { it.canExecute() }
        assumeTrue("the payload's Linux build is not here", binary != null)
        val port = ServerSocket(0).use { it.localPort }
        val proc = ProcessBuilder(binary!!.absolutePath).apply { environment()["UNPACK_PORT"] = port.toString() }.start()
        try {
            val dest = Files.createTempDirectory("unpack").toFile()
            val rnd = java.util.Random(3)
            val files = (0 until 300).associate { i -> "dir${i % 7}/f$i.bin" to ByteArray(rnd.nextInt(5000)).also { rnd.nextBytes(it) } } +
                mapOf("big.bin" to ByteArray(3 * 1024 * 1024 + 5).also { rnd.nextBytes(it) }, "empty.txt" to ByteArray(0))
            val stream = files.map { (rel, data) -> StreamFile(rel, data.size.toLong()) { data.inputStream() } }
            var moved = 0L
            val answer = connect(port).use { s ->
                writeFolderStream(s.getOutputStream().buffered(1 shl 20), dest.absolutePath, listOf("empty-dir", "dir1"), stream) { moved += it }
                s.getInputStream().bufferedReader().readLine()
            }
            assertEquals("OK ${files.size} ${files.values.sumOf { it.size.toLong() }}", answer)
            assertEquals(files.values.sumOf { it.size.toLong() }, moved)
            for ((rel, data) in files) assertArrayEquals(rel, data, File(dest, rel).readBytes())
            assertEquals(true, File(dest, "empty-dir").isDirectory)
        } finally {
            proc.destroy()
        }
    }

    private fun connect(port: Int): Socket {
        repeat(50) {
            try {
                return Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 1000) }
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        error("the payload does not answer")
    }
}
