package com.example.pssender

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class BackupTest {
    private val console = Profile(id = "p1", name = "Living room", host = "192.168.1.20", ftpPort = "2121", type = ConsoleType.PS4, color = 3)

    private fun sample() = BackupData(
        consoles = listOf(console),
        activeId = "p1",
        home = "https://example.com",
        bookmarks = listOf(Bookmark("Site", "https://site.com")),
        adblockOn = false,
        adblockCustom = "ads.example.com",
        shelf = listOf(ShelfFile("a.elf", byteArrayOf(1, 2, 3))),
        chains = listOf(Chain("c1", "Start", listOf(ChainStep("a.elf", 5)))),
        pins = mapOf("p1" to listOf("/data/homebrew")),
        cheats = mapOf("PPSA01234" to listOf(gold))
    )

    private val gold = Cheat(
        "g1", "Gold", 0x20002000L, ValueType.INT, "9999", group = "Money",
        sigHex = "0102030405060708", sigMask = "11110000", sigAt = 4, ptr = "5100:10,34"
    )

    @Test
    fun aBackupReadsBackTheSame() {
        val text = backupToJson(sample(), "1.99", savedAt = "2026-11-02T10:00:00Z")
        val read = parseBackup(text)
        assertNull(read.problem)
        val d = read.data!!
        assertEquals(listOf(console), d.consoles)
        assertEquals("p1", d.activeId)
        assertEquals("https://example.com", d.home)
        assertEquals(listOf(Bookmark("Site", "https://site.com")), d.bookmarks)
        assertEquals(false, d.adblockOn)
        assertEquals("ads.example.com", d.adblockCustom)
        assertArrayEquals(byteArrayOf(1, 2, 3), d.shelf.single().bytes)
        assertEquals(sample().chains, d.chains)
        assertEquals(mapOf("p1" to listOf("/data/homebrew")), d.pins)
        assertEquals(mapOf("PPSA01234" to listOf(gold)), d.cheats)
        assertEquals("2026-11-02", backupDay(d))
    }

    @Test
    fun partsMissingFromTheFileAreLeftAlone() {
        val d = parseBackup("""{"app": "Crimson Multitool", "format": 1, "consoles": []}""").data!!
        assertNull(d.bookmarks)
        assertNull(d.chains)
        assertNull(d.pins)
        assertNull(d.cheats)
        assertNull(d.adblockOn)
        assertNull(backupDay(d))
    }

    @Test
    fun restoredCheatsJoinTheOnesOnThePhone() {
        val onPhone = listOf(gold.copy(value = "1", frozen = true), Cheat("h1", "Health", 0x10L, ValueType.FLOAT, "100"))
        val fromFile = listOf(gold, Cheat("a1", "Ammo", 0x20L, ValueType.INT, "30", frozen = true))
        val merged = mergeCheats(onPhone, fromFile)
        assertEquals(listOf("g1", "h1", "a1"), merged.map { it.id })
        // The file's value wins; whether it is frozen stays as it is on the phone, and nothing new starts frozen
        assertEquals("9999", merged[0].value)
        assertEquals(true, merged[0].frozen)
        assertEquals(false, merged[2].frozen)
    }

    @Test
    fun otherFilesAreRefused() {
        assertEquals(BackupProblem.NOT_A_BACKUP, parseBackup("hello").problem)
        assertEquals(BackupProblem.NOT_A_BACKUP, parseBackup("""{"app": "Other"}""").problem)
        assertEquals(BackupProblem.TOO_NEW, parseBackup("""{"app": "Crimson Multitool", "format": 99}""").problem)
    }

    @Test
    fun anUnknownActiveConsoleFallsBackToTheFirst() {
        val text = backupToJson(sample().let { BackupData(it.consoles, "gone", it.home, null, null, null, emptyList()) }, "1", "x")
        assertEquals("p1", parseBackup(text).data!!.activeId)
    }

    @Test
    fun theShelfIsCutToSize() {
        val files = listOf(ShelfFile("a", ByteArray(6)), ShelfFile("b", ByteArray(6)), ShelfFile("c", ByteArray(3)))
        val (kept, skipped) = fitShelf(files, 10)
        assertEquals(listOf("a", "c"), kept.map { it.name })
        assertEquals(1, skipped)
    }

    @Test
    fun fileName() {
        assertEquals("crimson-multitool-backup-", backupFileName(Date()).take(25))
        assertEquals(true, backupFileName().endsWith(".json"))
    }

    @Test
    fun backupsFromBeforeTheRenameStillRestore() {
        // Made when the app was still called Bento
        val d = parseBackup("""{"app": "Bento", "format": 1, "consoles": []}""")
        assertEquals(null, d.problem)
        // And new ones carry the new name
        assertTrue(backupToJson(sample(), "1.0").contains("\"app\":\"Crimson Multitool\""))
    }
}
