package com.example.pssender

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReloadGuardTest {
    private val up: (String, Int) -> Boolean = { _, p -> p == ReloadGuard.ETAHEN_FTP_PORT }
    private val down: (String, Int) -> Boolean = { _, _ -> false }

    @After
    fun forget() = ReloadGuard.clear()

    @Test
    fun whichPayloadsCount() {
        assertEquals(ReloadKind.ETAHEN, ReloadGuard.kindOf("etaHEN-2.5B.bin"))
        assertEquals(ReloadKind.KSTUFF, ReloadGuard.kindOf("kstuff-lite_v1.11.elf"))
        assertEquals(ReloadKind.GOLDHEN, ReloadGuard.kindOf("goldhen.bin"))
        assertNull(ReloadGuard.kindOf("ftpsrv.elf"))
    }

    @Test
    fun otherPayloadsNeverAsk() {
        assertNull(ReloadGuard.warning("10.0.0.5", listOf("ftpsrv.elf", "websrv.elf"), ConsoleType.PS5, isOpen = up))
    }

    @Test
    fun aPs5WhereEtaHenAnswersAsksFirst() {
        assertNotNull(ReloadGuard.warning("10.0.0.5", listOf("etaHEN.bin"), ConsoleType.PS5, isOpen = up))
        assertNotNull(ReloadGuard.warning("10.0.0.5", listOf("ftpsrv.elf", "kstuff.elf"), ConsoleType.PS5, isOpen = up))
        assertNull(ReloadGuard.warning("10.0.0.5", listOf("etaHEN.bin"), ConsoleType.PS5, isOpen = down))
        // Port 1337 means nothing on a PS4
        assertNull(ReloadGuard.warning("10.0.0.5", listOf("goldhen.bin"), ConsoleType.PS4, isOpen = up))
    }

    @Test
    fun aSecondSendToTheSameConsoleAsks() {
        ReloadGuard.noteSent("10.0.0.5", "goldhen.bin", now = 1_000_000)
        assertNotNull(ReloadGuard.warning("10.0.0.5", listOf("goldhen.bin"), ConsoleType.PS4, now = 1_000_000 + 60_000, isOpen = down))
        // Another console, or long after (it has been restarted since), doesn't ask
        assertNull(ReloadGuard.warning("10.0.0.6", listOf("goldhen.bin"), ConsoleType.PS4, now = 1_060_000, isOpen = down))
        assertNull(ReloadGuard.warning("10.0.0.5", listOf("goldhen.bin"), ConsoleType.PS4, now = 1_000_000 + ReloadGuard.REMEMBER_MS, isOpen = down))
        // Sending ftpsrv is not noted
        ReloadGuard.noteSent("10.0.0.7", "ftpsrv.elf")
        assertNull(ReloadGuard.warning("10.0.0.7", listOf("ftpsrv.elf"), ConsoleType.PS5, isOpen = down))
    }
}
