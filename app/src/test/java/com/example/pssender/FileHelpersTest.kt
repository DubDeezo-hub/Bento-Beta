package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileHelpersTest {
    @Test
    fun zipEntryNamesStayUnique() {
        val used = HashSet<String>()
        assertEquals("game/save.dat", uniqueEntryName("game/save.dat", used))
        assertEquals("game/save (2).dat", uniqueEntryName("game/save.dat", used))
        // The phone sees SAVE.dat and save.dat as the same name; the file keeps its own capitals
        assertEquals("game/SAVE (3).dat", uniqueEntryName("game/SAVE.dat", used))
        assertEquals("game/other.dat", uniqueEntryName("game/other.dat", used))
        // No extension, and a dot in a folder name is not an extension
        assertEquals("v1.0/readme (2)", uniqueEntryName("v1.0/readme", used.apply { add("v1.0/readme") }))
        assertEquals(".hidden (2)", uniqueEntryName(".hidden", used.apply { add(".hidden") }))
    }

    @Test
    fun onlyRealUtf8IsOpenedAsText() {
        assertEquals("FTP=1\nnäme", strictUtf8("FTP=1\nnäme".toByteArray(Charsets.UTF_8)))
        // "näme" saved as Latin-1: decoding would replace the ä, and saving would change the file
        assertNull(strictUtf8("näme".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("", strictUtf8(ByteArray(0)))
    }
}
