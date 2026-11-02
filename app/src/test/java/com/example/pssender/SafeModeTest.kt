package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeModeTest {
    @Test
    fun ps5SendsKstuffThenEtahen() {
        val shelf = listOf("ftpsrv.elf", "etaHEN-2.5B.bin", "kstuff-lite_v1.11.elf", "ShadowMountPlus.elf")
        assertEquals(listOf("kstuff-lite_v1.11.elf", "etaHEN-2.5B.bin"), safeModeNames(shelf, ConsoleType.PS5))
    }

    @Test
    fun ps5NeedsBoth() {
        assertNull(safeModeNames(listOf("etaHEN-2.5B.bin"), ConsoleType.PS5))
        assertNull(safeModeNames(listOf("kstuff.elf"), ConsoleType.PS5))
    }

    @Test
    fun ps4SendsGoldhen() {
        assertEquals(listOf("goldhen_2.4b18.bin"), safeModeNames(listOf("goldhen_2.4b18.bin", "ftp.bin"), ConsoleType.PS4))
        assertNull(safeModeNames(listOf("ftp.bin"), ConsoleType.PS4))
    }

    @Test
    fun theNewestNameIsTaken() {
        val shelf = listOf("etaHEN-2.4B.bin", "etaHEN-2.5B.bin", "kstuff.elf")
        assertEquals("etaHEN-2.5B.bin", safeModeNames(shelf, ConsoleType.PS5)!![1])
    }

    @Test
    fun theChainPausesAfterKstuffOnly() {
        val c = safeModeChain(listOf("kstuff.elf", "etaHEN.bin"), "Safe mode")
        assertEquals(SAFE_MODE_ID, c.id)
        assertEquals(listOf(SAFE_MODE_WAIT, 0), c.steps.map { it.waitSeconds })
    }
}
