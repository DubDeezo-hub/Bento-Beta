package com.example.pssender

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

class CrashDiaryTest {
    @After
    fun forget() = SendLog.clear()

    @Test
    fun recentKeepsOnlyThisHostInsideTheWindow() {
        val now = 10_000_000L
        val window = CrashDiary.RECENT_WINDOW_MS
        SendLog.note("1.2.3.4", "etaHEN.bin", now - window + 1000) // inside
        SendLog.note("1.2.3.4", "kstuff.elf", now - window - 1000) // too old
        SendLog.note("9.9.9.9", "other.elf", now)                  // another console
        val got = SendLog.recent("1.2.3.4", window, now)
        assertEquals(1, got.size)
        assertEquals("etaHEN.bin", got[0].name)
    }

    @Test
    fun recentKeepsTheOrderTheyWereSent() {
        val now = 5_000_000L
        SendLog.note("h", "first.elf", now - 5000)
        SendLog.note("h", "second.elf", now - 1000)
        val got = SendLog.recent("h", CrashDiary.RECENT_WINDOW_MS, now)
        assertEquals(listOf("first.elf", "second.elf"), got.map { it.name })
        assertTrue(got.first().at <= got.last().at)
    }

    @Test
    fun noteIgnoresBlanks() {
        SendLog.note("", "x.elf")
        SendLog.note("h", "  ")
        assertTrue(SendLog.recent("h", CrashDiary.RECENT_WINDOW_MS).isEmpty())
    }

    @Test
    fun formatHasTheConsoleAndTheSentPayloads() {
        val now = Date(1_700_000_000_000L)
        val sent = listOf(SendLog.Send(1_699_999_000_000L, "1.2.3.4", "etaHEN.bin"))
        val text = CrashDiary.format("1.2.3.4", "Living room PS5", listOf("line one", "line two"), sent, now)
        assertTrue(text.contains("Living room PS5 (1.2.3.4)"))
        assertTrue(text.contains("etaHEN.bin"))
        assertTrue(text.contains("line two"))
        assertFalse(text.contains("(none)"))
    }

    @Test
    fun formatSaysNoneWhenNothingWasSent() {
        val text = CrashDiary.format("1.2.3.4", "", listOf("a line"), emptyList(), Date(0))
        assertTrue(text.contains("(none)"))
        // No name was given, so the address stands alone on the Console line (no empty brackets)
        assertTrue(text.contains("Console: 1.2.3.4\n"))
        assertFalse(text.contains("1.2.3.4 ("))
    }

    @Test
    fun formatKeepsOnlyTheLastLines() {
        val many = (1..100).map { "line $it" }
        val text = CrashDiary.format("h", "", many, emptyList(), Date(0))
        assertTrue(text.contains("line 100"))
        assertFalse(text.contains("line 60")) // older than the last 40 lines
    }

    @Test
    fun pruneKeepsTheNewestEntries() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cd-test-" + System.nanoTime()).apply { mkdirs() }
        try {
            for (i in 1..CrashDiary.MAX_ENTRIES + 5) {
                File(dir, "crash-2026-11-02_%02d-00-00.txt".format(i)).writeText("x")
            }
            CrashDiary.prune(dir)
            val left = dir.listFiles()!!.map { it.name }.sorted()
            assertEquals(CrashDiary.MAX_ENTRIES, left.size)
            // The oldest (01..05) are gone; the newest remain
            assertFalse(left.contains("crash-2026-11-02_01-00-00.txt"))
            assertTrue(left.contains("crash-2026-11-02_25-00-00.txt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parseWhenReadsTheFileName() {
        val ms = CrashDiary.parseWhen("crash-2026-11-02_09-30-00.txt")
        assertTrue(ms > 0)
        assertEquals(0L, CrashDiary.parseWhen("not-a-crash-file"))
    }
}
