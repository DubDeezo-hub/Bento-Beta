package com.example.pssender

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The debugger client against the practice console: the same pretend ps5debug-NG the Memory tab's practice game uses,
 * listening on 127.0.0.1. If the two ever stop agreeing on the wire format, these fail.
 */
class Ps5DebugTest {
    private var port = 0
    private val pid = PracticeGame.PID

    @Before
    fun start() {
        PracticeGame.restart()
        port = PracticeConsole.start()
    }

    @After
    fun stop() {
        PracticeConsole.stop()
    }

    private fun <T> debugger(block: (Ps5Debug) -> T): T = withDebugger("127.0.0.1", port, block)

    private fun int(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    @Test
    fun infoAndProcesses() = debugger { d ->
        d.ping()
        val info = d.info()
        assertEquals("1.3", info.protocol)
        assertEquals("10.40", info.firmware)
        assertEquals("1.1", info.capability)
        assertTrue(info.brand.startsWith("ps5debug-NG"))
        assertTrue(d.processes().any { it.pid == pid && it.name == "eboot.bin" })
        val app = d.foregroundApp()
        assertEquals(pid, app.pid)
        assertEquals(PracticeGame.TITLE_ID, app.titleId)
        assertEquals(PracticeGame.NAME, app.name)
    }

    @Test
    fun mapsReadAndWrite() = debugger { d ->
        val maps = d.maps(pid)
        assertEquals(PracticeGame.maps(), maps)
        assertEquals(PracticeGame.EBOOT, d.firstMapStart(pid))
        val gold = PracticeGame.goldAddress
        assertArrayEquals(int(PracticeGame.START_GOLD), d.read(pid, gold, 4))
        d.write(pid, gold, int(9999))
        assertEquals(9999, PracticeGame.gold())
        d.writeMulti(pid, listOf(gold to int(1234)))
        assertEquals(1234, PracticeGame.gold())
        d.notify("hello")
    }

    @Test
    fun searchNarrowsDownToTheGold() = debugger { d ->
        d.unlockScans()
        val heap = d.maps(pid).first { it.name == "heap" }
        val base = heap.start
        // First search: every place that holds 500
        val found = ArrayList<Int>()
        d.scanStart(pid, base, (heap.end - heap.start).toInt(), ValueType.INT, ScanCompare.EXACT.wire, int(PracticeGame.START_GOLD)) { off, _, _ ->
            found.add(off)
        }
        val goldOffset = (PracticeGame.goldAddress - base).toInt()
        assertTrue("several places hold 500", found.size > 1)
        assertTrue(goldOffset in found)
        // Spend some, then search again among those places for the new amount
        PracticeGame.spendGold(50)
        val left = ArrayList<Int>()
        val offsets = found.sorted().toIntArray()
        d.scanCount(pid, base, ValueType.INT, ScanCompare.EXACT.wire, int(450), offsets, ByteArray(0), offsets.size) { off, buf, at ->
            assertEquals(450, ByteBuffer.wrap(buf, at, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            left.add(off)
        }
        assertEquals(listOf(goldOffset), left)
    }

    @Test
    fun textSearchFindsTheHeroInBothEncodings() = debugger { d ->
        d.unlockScans()
        val heap = d.maps(pid).first { it.name == "heap" }
        val length = (heap.end - heap.start).toInt()
        for (enc in TextEncoding.entries) {
            val pattern = enc.encode(PracticeGame.HERO)
            val hits = ArrayList<Int>()
            d.scanBytes(pid, heap.start, length, pattern, ByteArray(pattern.size) { 1 }) { hits.add(it) }
            assertEquals("${enc.label} once", 1, hits.size)
            assertArrayEquals(pattern, d.read(pid, heap.start + hits[0], pattern.size))
        }
    }

    @Test
    fun aWrongPidReadsZerosAndRefusesWrites() = debugger { d ->
        assertArrayEquals(ByteArray(8), d.read(4242, PracticeGame.goldAddress, 8))
        try {
            d.write(4242, PracticeGame.goldAddress, int(1))
            throw AssertionError("the write should have been refused")
        } catch (_: DebuggerException) {
        }
        assertEquals(PracticeGame.START_GOLD, PracticeGame.gold())
    }
}
