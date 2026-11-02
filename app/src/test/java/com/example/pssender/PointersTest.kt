package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The pointer search against the practice game, whose health is reached by [eboot+5100] +10 → +34. */
class PointersTest {
    /** The practice game's memory, the way the debugger shows it. */
    private class GameMemory(val image: PointerScan.Image) : PointerScan.Memory {
        override fun pointersBetween(low: Long, high: Long, staticOnly: Boolean): List<PointerHit> {
            val out = ArrayList<PointerHit>()
            for (m in PracticeGame.maps()) {
                if (staticOnly && m.start !in image) continue
                val bytes = PracticeGame.read(m.start, (m.end - m.start).toInt()) ?: continue
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i + 8 <= bytes.size) {
                    val v = b.getLong(i)
                    if (v in low..high) out.add(PointerHit(m.start + i, v))
                    i += 8
                }
            }
            return out
        }

        override fun read64(addresses: LongArray): LongArray = LongArray(addresses.size) { i ->
            PracticeGame.read(addresses[i], 8)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long } ?: 0L
        }
    }

    private fun health() = PracticeGame.heapBase + 0x1200L + PracticeGame.PLAYER_HEALTH

    @Test
    fun findsThePracticeGamesPathAndItStillLeadsThereAfterARestart() {
        PracticeGame.restart()
        val image = PointerScan.imageOf(PracticeGame.maps(), "eboot.bin")!!
        assertEquals(PracticeGame.EBOOT, image.base)
        val mem = GameMemory(image)
        val paths = PointerScan.find(mem, health(), image)
        val expected = PointerPath(PracticeGame.ROOT_POINTER - PracticeGame.EBOOT, listOf(PracticeGame.WORLD_TO_PLAYER, PracticeGame.PLAYER_HEALTH))
        assertTrue("found: ${paths.map { it.text() }}", expected in paths)
        assertEquals("[eboot+5100] +10 → +34", expected.text())

        PracticeGame.restart()
        assertEquals(health(), PointerScan.resolve(mem, PracticeGame.EBOOT, listOf(expected))[0])
    }

    @Test
    fun aPathIsSavedAndReadBack() {
        val p = PointerPath(0x1A2B0, listOf(0x18, 0x40))
        assertEquals("1a2b0:18,40", p.encode())
        assertEquals(p, PointerPath.decode(p.encode()))
        assertNull(PointerPath.decode("nope"))
        assertNull(PointerPath.decode("10:"))
        assertNull(PointerPath.decode("zz:10"))
    }

    @Test
    fun theEbootTakesItsUnnamedDataAlong() {
        val maps = listOf(
            DbgMap("eboot.bin", 0x400000, 0x404000, 0, 5),
            DbgMap("eboot.bin", 0x404000, 0x406000, 0, 3),
            DbgMap("", 0x406000, 0x407000, 0, 3),
            DbgMap("", 0x500000, 0x501000, 0, 3)
        )
        val image = PointerScan.imageOf(maps)!!
        assertEquals(0x400000L, image.base)
        assertEquals(0x407000L, image.end)
        assertNull(PointerScan.imageOf(listOf(DbgMap("libc", 0, 10, 0, 5))))
    }

    @Test
    fun aNullPointerOnTheWayGivesZero() {
        val mem = object : PointerScan.Memory {
            override fun pointersBetween(low: Long, high: Long, staticOnly: Boolean) = emptyList<PointerHit>()
            override fun read64(addresses: LongArray) = LongArray(addresses.size)
        }
        assertEquals(0L, PointerScan.resolve(mem, 0x400000, listOf(PointerPath(0x10, listOf(0x8))))[0])
    }

    @Test
    fun nearbyAddressesShareOneWindow() {
        val c = PointerScan.clusters(listOf(0x1000L, 0x2000L, 0x90000000L), 0x100)
        assertEquals(2, c.size)
        assertEquals(0x1000L - 0x100, c[0].first)
        assertEquals(0x2000L, c[0].last)
    }
}
