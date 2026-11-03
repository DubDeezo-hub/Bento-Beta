package com.example.pssender

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatFilesTest {
    private val file = """
        {
          "name": "Some Game", "id": "PPSA01234", "version": "01.000", "process": "eboot.bin",
          "master": {"memory": [{"offset": "0x10", "on": "90", "off": "00"}]},
          "mods": [
            {"name": "Infinite Health", "type": "checkbox", "hint": "keeps you alive",
             "memory": [{"offset": "1A2B", "on": "90 90", "off": "29C8"}]},
            {"name": "Add Money", "type": "button", "memory": [{"offset": "0x20", "on": "0xFF"}]},
            {"name": "PS4 section", "type": "checkbox", "memory": [{"offset": "10", "on": "90", "off": "00", "section": "2"}]},
            {"name": "", "type": "checkbox", "memory": []}
          ],
          "credits": ["someone"]
        }
    """.trimIndent()

    @Test
    fun aCheatFileReads() {
        val c = CheatFiles.parse("﻿" + file)
        assertEquals("Some Game", c.name)
        assertEquals("PPSA01234", c.titleId)
        assertEquals(1, c.master.size)
        assertEquals(4, c.mods.size)
        val health = c.mods[0]
        assertFalse(health.button)
        assertEquals("keeps you alive", health.hint)
        assertEquals(0x1A2BL, health.patches[0].offset)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x90.toByte()), health.patches[0].on)
        assertTrue(health.supported)
        assertTrue(c.mods[1].button)
        assertEquals(0, c.mods[1].patches[0].off.size)
        // A patch for another section, and a mod without patches, are not offered
        assertFalse(c.mods[2].supported)
        assertFalse(c.mods[3].supported)
        assertEquals("?", c.mods[3].name)
        assertEquals(listOf("someone"), c.credits)
    }

    @Test(expected = IllegalArgumentException::class)
    fun somethingElseIsRefused() {
        CheatFiles.parse("[1, 2, 3]")
    }

    @Test
    fun aFileThisAppWritesReadsBackTheSame() {
        val mod = CheatFiles.modJson("Gold \"max\"", 0x1100, byteArrayOf(0x3F, 0x42), byteArrayOf(0, 1))
        val text = CheatFiles.fileJson("My Game", "CUSA00001", " 01.00 ", "eboot.bin", listOf(mod))
        val c = CheatFiles.parse(text)
        assertEquals("01.00", c.version)
        assertEquals("Gold \"max\"", c.mods.single().name)
        assertEquals(0x1100L, c.mods.single().patches.single().offset)
        assertArrayEquals(byteArrayOf(0, 1), c.mods.single().patches.single().off)
        assertEquals("CUSA00001_01.00.json", CheatFiles.fileName("CUSA00001", "01.00"))
    }

    @Test
    fun theIndexAndVersions() {
        val index = CheatFiles.parseIndex("﻿PPSA01628_01.026.000.json=Game A\nCUSA00016_01.00_2.json=Game B\nREADME.md=no\njunk\n")
        assertEquals(2, index.size)
        assertEquals("PPSA01628", index[0].titleId)
        assertEquals("01.026.000", index[0].version)
        assertEquals("01.00", index[1].version)
        assertTrue(CheatFiles.sameVersion("1.026.000", "01.026.000"))
        assertTrue(CheatFiles.sameVersion("01.00", "1.0"))
        assertFalse(CheatFiles.sameVersion("01.10", "01.01"))
        assertFalse(CheatFiles.sameVersion("", ""))
    }

    @Test
    fun theFilesForTheGamesVersionComeFirst() {
        val index = listOf(
            CheatIndexEntry("PPSA01234_01.000.json", "G"),
            CheatIndexEntry("PPSA01234_01.020.json", "G"),
            CheatIndexEntry("PPSA09999_01.020.json", "Other")
        )
        assertEquals(listOf("PPSA01234_01.020.json", "PPSA01234_01.000.json"), CheatFiles.forGame(index, "ppsa01234", "1.020").map { it.file })
    }

    @Test
    fun sourcesAndMerging() {
        assertEquals(listOf(CheatFiles.GOLDHEN_SOURCE, CheatFiles.DEFAULT_SOURCE), CheatFiles.sourcesFor(CheatFiles.DEFAULT_SOURCE, "CUSA00001"))
        assertEquals(listOf(CheatFiles.DEFAULT_SOURCE), CheatFiles.sourcesFor(CheatFiles.DEFAULT_SOURCE, "PPSA00001"))
        assertEquals(listOf("http://x/"), CheatFiles.sourcesFor("http://x/", "CUSA00001"))
        val merged = CheatFiles.mergeChoices(listOf(listOf(CheatIndexEntry("A.json", "first")), listOf(CheatIndexEntry("a.JSON", "second"), CheatIndexEntry("b.json", "b"))))
        assertEquals(listOf("first", "b"), merged.map { it.game })
    }

    @Test
    fun hexText() {
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x0A), CheatFiles.hex("90 0a"))
        assertArrayEquals(byteArrayOf(0x90.toByte()), CheatFiles.hex("0x90"))
        assertNull(CheatFiles.hex("909"))
        assertNull(CheatFiles.hex("zz"))
    }
}
