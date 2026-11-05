package com.example.pssender

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Downloads in parts against a small FTP server that behaves like ftpsrv (REST, then RETR sends to the end of the file). */
class SplitDownloadTest {
    private val file = ByteArray(10 * 1024 * 1024 + 4321) { i -> ((i * 31) xor (i ushr 11)).toByte() }
    private lateinit var server: MiniFtp

    @Before
    fun start() {
        server = MiniFtp(mapOf("/big.bin" to file))
    }

    @After
    fun stop() = server.close()

    /** The phone's copy, in memory, writable at any place. */
    private class MemorySink(size: Int) : ResumableSink {
        val bytes = ByteArray(size)
        @Volatile
        var end = 0L

        override fun storedLength(): Long = end
        override fun open(offset: Long): OutputStream = openAt(offset)
        override fun openAt(offset: Long): OutputStream = object : OutputStream() {
            var at = offset.toInt()
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(bytes) {
                    System.arraycopy(b, off, bytes, at, len)
                    at += len
                    if (at > end) end = at.toLong()
                }
            }
        }
    }

    private val hooks = object : TransferHooks {
        override val cancelled = false
        override fun starting(done: Long, total: Long) {}
        override fun progress(done: Long, total: Long) {}
        override fun status(text: String) {}
        override fun setAbort(hook: (() -> Unit)?) {}
    }

    @Test
    fun aBigFileComesDownInPartsUnchanged() {
        val sink = MemorySink(file.size)
        val s = FtpSettings("127.0.0.1", server.port, "anonymous", "")
        val problem = splitDownload(sink, "/big.bin", file.size.toLong(), s, 4, hooks)
        assertNull(problem)
        assertEquals(file.size.toLong(), sink.storedLength())
        assertArrayEquals(file, sink.bytes)
        assertEquals(4, server.retrieves) // one per part
    }

    @Test
    fun aSinkThatCanOnlyBeWrittenInOrderIsLeftToTheUsualWay() {
        val inOrder = object : ResumableSink {
            override fun storedLength() = 0L
            override fun open(offset: Long): OutputStream = OutputStream.nullOutputStream()
        }
        val s = FtpSettings("127.0.0.1", server.port, "anonymous", "")
        assertEquals(SPLIT_NOT_POSSIBLE, splitDownload(inOrder, "/big.bin", file.size.toLong(), s, 4, hooks))
        assertEquals(0, server.retrieves)
    }
}

/** Just enough of an FTP server: login, passive mode, SIZE, REST and RETR (to the end of the file, from the REST point). */
class MiniFtp(private val files: Map<String, ByteArray>) : AutoCloseable {
    private val control = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = control.localPort

    @Volatile
    var retrieves = 0

    init {
        thread(isDaemon = true) {
            while (!control.isClosed) {
                val c = try {
                    control.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(c) }
            }
        }
    }

    private fun serve(c: Socket) = c.use {
        val inp = BufferedReader(InputStreamReader(c.getInputStream()))
        val out = c.getOutputStream()
        fun say(line: String) = out.write("$line\r\n".toByteArray()).also { out.flush() }
        say("220 mini")
        var passive: ServerSocket? = null
        var offset = 0L
        while (true) {
            val line = inp.readLine() ?: break
            val cmd = line.substringBefore(' ').uppercase()
            val arg = line.substringAfter(' ', "")
            when (cmd) {
                "USER" -> say("331 ok")
                "PASS" -> say("230 ok")
                "TYPE", "NOOP" -> say("200 ok")
                "PASV" -> {
                    passive?.close()
                    val p = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                    passive = p
                    say("227 Entering Passive Mode (127,0,0,1,${p.localPort / 256},${p.localPort % 256})")
                }
                "SIZE" -> files[arg]?.let { say("213 ${it.size}") } ?: say("550 no")
                "REST" -> {
                    offset = arg.trim().toLong()
                    say("350 ok")
                }
                "RETR" -> {
                    val data = files[arg]
                    val p = passive
                    if (data == null || p == null) {
                        say("550 no")
                    } else {
                        synchronized(this) { retrieves++ }
                        say("150 sending")
                        val ok = try {
                            p.accept().use { d -> d.getOutputStream().write(data, offset.toInt(), data.size - offset.toInt()) }
                            true
                        } catch (_: Exception) {
                            false // the client hung up early, as a part does
                        }
                        p.close()
                        passive = null
                        offset = 0L
                        say(if (ok) "226 done" else "426 aborted")
                    }
                }
                "QUIT" -> {
                    say("221 bye")
                    break
                }
                else -> say("502 no")
            }
        }
    }

    override fun close() = control.close()
}
