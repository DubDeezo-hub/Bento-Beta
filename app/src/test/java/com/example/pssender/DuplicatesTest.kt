package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private val mb = 1L shl 20

    @Test
    fun sameNameAndSizeAreCopies() {
        val files = mapOf(
            "pkgs/game.pkg" to 900 * mb, "old/Game.pkg" to 900 * mb,   // a copy (capitals don't matter)
            "x/game.pkg" to 800 * mb,                                     // same name, other size: not a copy
            "a/etaHEN.bin" to 2 * mb, "b/etaHEN.bin" to 2 * mb, "c/etaHEN.bin" to 2 * mb
        )
        val g = duplicateGroups(files)
        assertEquals(2, g.size)
        assertEquals(listOf("old/Game.pkg", "pkgs/game.pkg"), g[0].paths) // most space wasted first
        assertEquals(900 * mb, g[0].extra)
        assertEquals(3, g[1].paths.size)
        assertEquals(4 * mb, g[1].extra)
    }

    @Test
    fun smallFilesAreLeftOut() {
        val files = mapOf("a/icon0.png" to 40_000L, "b/icon0.png" to 40_000L)
        assertTrue(duplicateGroups(files).isEmpty())
    }
}
