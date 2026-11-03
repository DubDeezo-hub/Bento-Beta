package com.example.pssender

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartChainCompatHelperTest {
    @After
    fun tidy() {
        SmartChain.on = true
    }

    // --- smart chains ---

    @Test
    fun waitsForTheSignOfLifeThenGoesOn() = runBlocking {
        val sent = ArrayList<String>()
        var looks = 0
        var slept = 0
        val o = runChain(
            listOf(ChainStep("etaHEN.bin", 0), ChainStep("ftpsrv.elf")),
            send = { sent.add(it); PayloadReply(true, "ok") },
            sleepSecond = { slept++ },
            proof = { name -> if (name.startsWith("etaHEN")) (++looks > 3) else null }
        )
        assertTrue(o.ok)
        assertEquals(listOf("etaHEN.bin", "ftpsrv.elf"), sent)
        assertEquals(2, slept) // the first look finds a sign to wait for; the fourth says it is up
    }

    @Test
    fun stopsWhenTheSignNeverComes() = runBlocking {
        val sent = ArrayList<String>()
        val o = runChain(
            listOf(ChainStep("etaHEN.bin"), ChainStep("ftpsrv.elf")),
            send = { sent.add(it); PayloadReply(true, "ok") },
            sleepSecond = {},
            proof = { false },
            proofSeconds = 5
        )
        assertFalse(o.ok)
        assertEquals(listOf("etaHEN.bin"), sent) // the next payload never went
        assertEquals(1, o.failedStep)
    }

    @Test
    fun theRestOfALongerPauseStillApplies() = runBlocking {
        var slept = 0
        var looks = 0
        runChain(
            listOf(ChainStep("etaHEN.bin", 10), ChainStep("b.elf")),
            send = { PayloadReply(true, "ok") },
            sleepSecond = { slept++ },
            proof = { (++looks > 4) }
        )
        assertEquals(10, slept) // 3 waiting for it, then the 7 left of the 10-second pause
    }

    @Test
    fun onlyEtaHenHasASignAndTheSwitchTurnsItOff() {
        val open: (String, Int) -> Boolean = { _, port -> port == ReloadGuard.ETAHEN_FTP_PORT }
        assertEquals(true, SmartChain.proof("etaHEN-2.5B.bin", "10.0.0.5", open))
        assertNull(SmartChain.proof("kstuff.elf", "10.0.0.5", open))
        SmartChain.on = false
        assertNull(SmartChain.proof("etaHEN.bin", "10.0.0.5", open))
    }

    // --- shared compatibility list ---

    @Test
    fun versionsCountTogether() {
        assertEquals("etahen", compatKey("etaHEN-2.5B.bin"))
        assertEquals("etahen", compatKey("etaHEN.bin"))
        assertEquals("kstuff-lite", compatKey("kstuff-lite v1.11.elf"))
        assertEquals("shadowmountplus", compatKey("ShadowMountPlus_1.7.elf"))
        assertEquals("123.elf", compatKey("123.elf")) // nothing left after the version: the whole name
    }

    @Test
    fun readsTheCounts() {
        val json = """{"version":1,"reports":{"etahen":{"PS5 10.40":{"worked":3,"crashed":1,"failed":0}}},"seen":[]}"""
        val c = parseCompat(json)["etahen"]!![compatWhere("PS5", "10.40")]!!
        assertEquals(CompatCounts(3, 1, 0), c)
        assertEquals(4, c.total)
        assertTrue(parseCompat("not json").isEmpty())
        assertTrue(parseCompat(null).isEmpty())
    }

    @Test
    fun aReportHoldsOnlyItsFields() {
        val body = compatReportBody("etaHEN-2.5B.bin", "2.5B", "PS5", "10.40", CompatResult.CRASHED)
        assertTrue(body.startsWith("<!-- crimson-compat -->\n"))
        assertTrue("payload: etaHEN-2.5B.bin\n" in body)
        assertTrue("firmware: 10.40\n" in body)
        assertTrue("result: crashed\n" in body)
        // Odd characters in a file name are left out, so the workflow can always read it
        assertTrue("payload: evilnamehere.bin\n" in compatReportBody("evil<name>\nhere.bin", "", "PS5", "10.40", CompatResult.WORKED))
    }

    // --- cheat-finding helper ---

    @Test
    fun helperStepsFollowWhatIsLeft() {
        assertEquals(HelperStage.START, helperStage(-1))
        assertEquals(HelperStage.NONE, helperStage(0))
        assertEquals(HelperStage.FEW, helperStage(HELPER_FEW.toLong()))
        assertEquals(HelperStage.NARROW, helperStage(5000))
    }

    @Test
    fun helperPicksTheRightKindOfNumber() {
        assertEquals(ValueType.INT, helperStartType(HelperKind.NUMBER, decimals = false))
        assertEquals(ValueType.FLOAT, helperStartType(HelperKind.NUMBER, decimals = true))
        assertEquals(ValueType.FLOAT, helperStartType(HelperKind.BAR, decimals = false))
        assertEquals(ScanCompare.INCREASED, HelperChange.UP.compare)
        assertEquals(ScanCompare.UNCHANGED, HelperChange.SAME.compare)
    }
}
