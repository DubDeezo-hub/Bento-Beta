package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZipUploadTest {
    @Test
    fun climbingOutIsNotPossible() {
        assertEquals(listOf("etc", "passwd"), zipEntryParts("../../etc/passwd"))
        assertEquals(listOf("a", "b.txt"), zipEntryParts("a\\..\\b.txt".replace("..\\", "")))
        assertEquals(listOf("x", "y.bin"), zipEntryParts("/x/./y.bin"))
    }

    @Test
    fun emptyNamesGiveNothing() {
        assertNull(zipEntryParts("../"))
        assertNull(zipEntryParts("  "))
    }

    @Test
    fun oddCharactersAreMadeSafe() {
        assertEquals(listOf("a_b.txt"), zipEntryParts("a:b.txt"))
    }

    @Test
    fun aSharedTopFolderIsFound() {
        assertEquals("game", zipSharedTop(listOf(listOf("game", "eboot.bin"), listOf("game", "sce_sys", "icon0.png"))))
        assertNull(zipSharedTop(listOf(listOf("game", "a.bin"), listOf("other", "b.bin"))))
        assertNull(zipSharedTop(listOf(listOf("loose.bin"))))
    }
}
