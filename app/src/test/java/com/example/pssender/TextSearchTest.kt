package com.example.pssender

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    @Test
    fun patternsInBothEncodings() {
        val p = TextSearch.patterns("Ab", listOf(TextEncoding.UTF8, TextEncoding.UTF16))
        assertArrayEquals(byteArrayOf(0x41, 0x62), p[0].second)
        assertArrayEquals(byteArrayOf(0x41, 0, 0x62, 0), p[1].second)
        assertTrue(TextSearch.patterns("", TextEncoding.entries).isEmpty())
    }

    @Test
    fun piecesOverlapSoNothingAtACutIsMissed() {
        val big = 0x40000000L
        val maps = listOf(DbgMap("b", 0x100000000L, 0x100000000L + 0x1000, 0, 3), DbgMap("a", 0, big * 2 + 10, 0, 3))
        val pieces = TextSearch.pieces(maps, 5)
        assertEquals(0L to big.toInt(), pieces[0])
        assertEquals(big - 5, pieces[1].first)
        // Every byte of the first region is in some piece, and the pieces end where the region ends
        val ends = pieces.filter { it.first < big * 3 }.map { it.first + it.second }
        assertEquals(big * 2 + 10, ends.max())
        assertEquals(0x100000000L to 0x1000, pieces.last())
    }

    @Test
    fun readableStopsAtTheStringsEnd() {
        val after = "rith".toByteArray() + 0 + "junk".toByteArray()
        assertEquals("rith", TextSearch.readable(after, TextEncoding.UTF8, fromEnd = false))
        val before = "junk".toByteArray() + 0 + "Hero: ".toByteArray()
        assertEquals("Hero: ", TextSearch.readable(before, TextEncoding.UTF8, fromEnd = true))
        val wide = "Hi".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0) + "x".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hi", TextSearch.readable(wide, TextEncoding.UTF16, fromEnd = false))
        // Control characters show as dots
        assertEquals("a·b", TextSearch.readable(byteArrayOf(0x61, 0x01, 0x62), TextEncoding.UTF8, fromEnd = false))
    }

    @Test
    fun replacementFitsOrIsRefused() {
        assertArrayEquals(byteArrayOf(0x42, 0x6F, 0x62, 0, 0, 0), TextSearch.replacement("Bob", TextEncoding.UTF8, 6))
        assertNull(TextSearch.replacement("Longer name", TextEncoding.UTF8, 6))
        assertEquals(12, TextSearch.replacement("Bob", TextEncoding.UTF16, 12)!!.size)
        assertNull(TextSearch.replacement("Aerith!", TextEncoding.UTF16, 12))
    }
}
