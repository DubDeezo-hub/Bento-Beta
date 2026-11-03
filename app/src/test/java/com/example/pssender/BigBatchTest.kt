package com.example.pssender

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BigBatchTest {
    @After
    fun tidy() {
        CompatData.use(null)
        SmartChain.on = true
    }

    // --- shared results in the warning before sending ---

    @Test
    fun warnsWhenOthersMostlySawItCrash() {
        val data = mapOf("etahen" to mapOf("PS5 10.40" to CompatCounts(1, 3, 0), "PS5 9.60" to CompatCounts(5, 0, 0)))
        assertNotNull(compatWarning(listOf("etaHEN-2.5B.bin"), "PS5", "10.40", data))
        assertNull(compatWarning(listOf("etaHEN.bin"), "PS5", "9.60", data)) // worked there
        assertNull(compatWarning(listOf("etaHEN.bin"), "PS5", null, data)) // firmware unknown
        assertNull(compatWarning(listOf("etaHEN.bin"), "PS5", "10.40", mapOf("etahen" to mapOf("PS5 10.40" to CompatCounts(0, 1, 0))))) // one report isn't enough
    }

    // --- quick chain for the tile, the widget and the online alert ---

    @Test
    fun theConsolesOwnChainComesFirst() {
        val a = Chain("a", "A", listOf(ChainStep("x.elf")))
        val b = Chain("b", "B", listOf(ChainStep("y.elf")))
        assertEquals("b", pickQuickChain(listOf(a, b), "b")?.id)
        assertEquals("a", pickQuickChain(listOf(a, b), "")?.id)
        assertEquals("a", pickQuickChain(listOf(a, b), "gone")?.id)
        assertNull(pickQuickChain(emptyList(), "b"))
    }

    // --- sending to several consoles ---

    @Test
    fun aRunToAllSaysWhichDidNotTakeIt() {
        // AppText isn't set up in the tests: only that it gives an answer is checked, and the logic by its branch
        assertNotNull(sendAllSummary("kstuff.elf", listOf("Living room" to true, "Bedroom" to true)))
        assertNotNull(sendAllSummary("kstuff.elf", listOf("Living room" to true, "Bedroom" to false)))
    }

    // --- smart chain: stop when the console goes silent ---

    @Test
    fun aSilentConsoleStopsTheChainDuringAPause() = runBlocking {
        val sent = ArrayList<String>()
        var silent = false
        var slept = 0
        val o = runChain(
            listOf(ChainStep("kstuff.elf", 10), ChainStep("etaHEN.bin")),
            send = { sent.add(it); PayloadReply(true, "ok") },
            sleepSecond = { slept++; if (slept == 3) silent = true },
            trouble = { if (silent) "silent" else null }
        )
        assertFalse(o.ok)
        assertEquals(listOf("kstuff.elf"), sent)
        assertEquals(3, slept)
    }

    @Test
    fun silenceOnlyCountsAfterTheChainStarted() {
        assertNull(SmartChain.trouble(startedAt = 1000, silentAt = 0))
        assertNull(SmartChain.trouble(startedAt = 1000, silentAt = 500))
        assertNotNull(SmartChain.trouble(startedAt = 1000, silentAt = 1500))
        SmartChain.on = false
        assertNull(SmartChain.trouble(startedAt = 1000, silentAt = 1500))
    }

    // --- settings snapshots ---

    @Test
    fun snapshotsSeeChangesNewAndGone() {
        val saved = mapOf("config.ini" to "a=1".toByteArray(), "list.txt" to "x".toByteArray(), "old.txt" to "o".toByteArray())
        val now = mapOf("config.ini" to "a=2".toByteArray(), "list.txt" to "x".toByteArray(), "new.txt" to "n".toByteArray())
        val d = snapshotDiff(saved, now)
        assertEquals(listOf("config.ini"), d.changed)
        assertEquals(listOf("new.txt"), d.added)
        assertEquals(listOf("old.txt"), d.removed)
        assertTrue(snapshotDiff(saved, saved).same)
    }

    @Test
    fun snapshotsKeepOnlySmallSettingsFiles() {
        val pick = snapshotPick(mapOf("config.ini" to 200L, "big.elf" to 5_000_000L, "config.ini.bak" to 200L, "cheats/a.json" to 1000L))
        assertEquals(listOf("cheats/a.json", "config.ini"), pick)
    }

    // --- checklist ---

    @Test
    fun theSuggestedOrderFollowsTheRules() {
        val shelf = listOf("etaHEN-2.5B.bin", "ShadowMountPlus.elf", "kstuff-lite.elf", "elfldr.elf", "ftpsrv.elf")
        val (order, missing) = suggestedOrder(shelf, ConsoleType.PS5)
        assertEquals(listOf("kstuff-lite.elf", "ShadowMountPlus.elf", "etaHEN-2.5B.bin"), order)
        assertEquals(0, missing)
        assertNull(ChainOrder.check(order, ConsoleType.PS5)) // the load-order rules agree
        assertEquals(1, suggestedOrder(listOf("etaHEN.bin", "kstuff.elf"), ConsoleType.PS5).second)
        assertEquals(listOf("goldhen.bin"), suggestedOrder(listOf("goldhen.bin", "other.bin"), ConsoleType.PS4).first)
    }

    // --- developer mode: the ELF check ---

    private fun elf(machine: Int = 0x3E, cls: Int = 2, loadSize: Long = 16, total: Int = 256): ByteArray {
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0x7F); b.put(1, 'E'.code.toByte()); b.put(2, 'L'.code.toByte()); b.put(3, 'F'.code.toByte())
        b.put(4, cls.toByte()); b.put(5, 1)
        b.putShort(18, machine.toShort())
        b.putLong(32, 64) // program headers right after the ELF header
        b.putShort(54, 56) // one program header of 56 bytes
        b.putShort(56, 1)
        b.putInt(64, 1) // PT_LOAD
        b.putLong(64 + 8, 128) // its data at 128
        b.putLong(64 + 32, loadSize)
        return b.array()
    }

    @Test
    fun theElfCheck() {
        assertEquals(ElfVerdict.OK, elfCheck("p.elf", elf()))
        assertEquals(ElfVerdict.NOT_64, elfCheck("p.elf", elf(cls = 1)))
        assertEquals(ElfVerdict.WRONG_MACHINE, elfCheck("p.elf", elf(machine = 0xB7))) // ARM64
        assertEquals(ElfVerdict.TRUNCATED, elfCheck("p.elf", elf(loadSize = 4096)))
        assertEquals(ElfVerdict.NOT_ELF, elfCheck("p.elf", ByteArray(100)))
        assertNull(elfCheck("payload.bin", ByteArray(100))) // a raw .bin isn't checked
    }

    @Test
    fun devBuildsGetTheirOwnShelfName() {
        assertEquals("dev-hello.elf", devName("https://github.com/me/x/releases/download/v1/hello.elf?raw=1"))
        assertEquals("dev-payload.elf", devName("https://example.com/"))
    }
}
