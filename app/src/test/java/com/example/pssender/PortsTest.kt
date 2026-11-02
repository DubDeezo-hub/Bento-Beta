package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Test

class PortsTest {
    @Test
    fun aNewPs5UsesEtaHensOwnFtp() {
        assertEquals("1337", Profile(id = "a", name = "A").ftpPort)
        assertEquals("1337", ConsoleType.PS5.ftpPort)
        assertEquals("2121", ConsoleType.PS4.ftpPort)
    }

    @Test
    fun aFreshInstallGetsTheNewDefaultButOldSettingsStay() {
        assertEquals("1337", legacyProfile({ _, default -> default }).ftpPort)
        assertEquals("2121", legacyProfile({ key, default -> if (key == "ftp_port") "2121" else default }).ftpPort)
    }

    @Test
    fun switchingTypeMovesOnlyUsualPorts() {
        val ps5 = Profile(id = "a", name = "A")
        val ps4 = ps5.withType(ConsoleType.PS4)
        assertEquals("2121", ps4.ftpPort)
        assertEquals("9090", ps4.payloadPort)
        assertEquals("1337", ps4.withType(ConsoleType.PS5).ftpPort)
        // A port typed by hand stays
        assertEquals("2222", ps5.copy(ftpPort = "2222").withType(ConsoleType.PS4).ftpPort)
        // A console saved with the old PS5 default (the ftpsrv payload's port) is not moved
        assertEquals("2121", ps5.copy(ftpPort = "2121").withType(ConsoleType.PS4).ftpPort)
    }

    @Test
    fun diagnoseTriesEtaHensFtpFirstOnAPs5() {
        assertEquals(listOf(1337, 2121, 21), Diagnose.alternatives(ConsoleType.PS5, DiagService.FTP))
        assertEquals(listOf(2121, 21, 1337), Diagnose.alternatives(ConsoleType.PS4, DiagService.FTP))
    }

    @Test
    fun consoleLogTriesTheOtherUsualPort() {
        assertEquals(listOf(3232, 9081), klogPorts(3232))
        assertEquals(listOf(9081, 3232), klogPorts(9081))
        assertEquals(listOf(5000), klogPorts(5000))
    }
}
