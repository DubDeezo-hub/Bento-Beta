package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortsTest {
    @Test
    fun aNewPs5UsesTheStandalonePayloads() {
        val p = Profile(id = "a", name = "A")
        assertEquals("2121", p.ftpPort)
        assertEquals("9021", p.payloadPort)
        assertFalse(p.etaHen)
        assertEquals("2121", ConsoleType.PS5.ftpPort)
        assertEquals("2121", ConsoleType.PS4.ftpPort)
    }

    @Test
    fun aFreshInstallGetsTheNewDefaultButOldSettingsStay() {
        assertEquals("2121", legacyProfile({ _, default -> default }).ftpPort)
        assertFalse(legacyProfile({ _, default -> default }).etaHen)
        val old = legacyProfile({ key, default -> if (key == "ftp_port") "1337" else default })
        assertEquals("1337", old.ftpPort)
        assertTrue(old.etaHen)
    }

    @Test
    fun switchingTypeMovesOnlyUsualPorts() {
        val ps5 = Profile(id = "a", name = "A")
        val ps4 = ps5.withType(ConsoleType.PS4)
        assertEquals("2121", ps4.ftpPort)
        assertEquals("9090", ps4.payloadPort)
        assertEquals("2121", ps4.withType(ConsoleType.PS5).ftpPort)
        // A PS5 on etaHEN's FTP goes to the PS4's usual port, and etaHEN is off on a PS4
        val eta = ps5.withEtaHen(true)
        assertEquals("2121", eta.withType(ConsoleType.PS4).ftpPort)
        assertFalse(eta.withType(ConsoleType.PS4).etaHen)
        // A port typed by hand stays
        assertEquals("2222", ps5.copy(ftpPort = "2222").withType(ConsoleType.PS4).ftpPort)
    }

    @Test
    fun etaHenIsOptional() {
        val ps5 = Profile(id = "a", name = "A")
        val on = ps5.withEtaHen(true)
        assertTrue(on.etaHen)
        assertEquals("1337", on.ftpPort)
        assertEquals("9021", on.payloadPort)
        assertEquals("2121", on.withEtaHen(false).ftpPort)
        // A port typed by hand stays either way
        assertEquals("2222", ps5.copy(ftpPort = "2222").withEtaHen(true).ftpPort)
        // A PS4 never runs etaHEN
        assertFalse(ps5.withType(ConsoleType.PS4).withEtaHen(true).etaHen)
        assertEquals("elfldr", henName(ConsoleType.PS5, false))
        assertEquals("etaHEN", henName(ConsoleType.PS5, true))
        assertEquals("GoldHEN", henName(ConsoleType.PS4, false))
    }

    @Test
    fun theFtpPortFollowsWhatRuns() {
        val plain = Profile(id = "a", name = "A")
        val eta = plain.withEtaHen(true)
        // etaHEN loaded: 2121 is quiet, 1337 answers
        assertEquals(eta, plain.followRunning(setFtpUp = false, otherFtpUp = true))
        // Started without etaHEN: 1337 is quiet, ftpsrv's 2121 answers
        assertEquals(plain, eta.followRunning(setFtpUp = false, otherFtpUp = true))
        // Nothing to change when the set port answers or neither does
        assertNull(plain.followRunning(setFtpUp = true, otherFtpUp = true))
        assertNull(plain.followRunning(setFtpUp = false, otherFtpUp = false))
        // A port typed by hand and a PS4 are left alone
        assertNull(plain.copy(ftpPort = "2222").followRunning(setFtpUp = false, otherFtpUp = true))
        assertNull(plain.withType(ConsoleType.PS4).followRunning(setFtpUp = false, otherFtpUp = true))
        assertEquals(1337, otherUsualFtp("2121"))
        assertEquals(2121, otherUsualFtp(" 1337"))
        assertNull(otherUsualFtp("21"))
    }

    @Test
    fun savedConsolesKeepTheirSetup() {
        val list = listOf(Profile(id = "a", name = "A").withEtaHen(true), Profile(id = "b", name = "B"))
        assertEquals(list, parseProfiles(profilesToJson(list)))
        // Saved before the switch: etaHEN's FTP port means etaHEN
        val old = parseProfiles("""[{"id":"x","name":"X","ftpPort":"1337","type":"ps5"},{"id":"y","name":"Y","ftpPort":"2121","type":"ps5"}]""")
        assertTrue(old[0].etaHen)
        assertFalse(old[1].etaHen)
    }

    @Test
    fun diagnoseTriesFtpsrvFirst() {
        assertEquals(listOf(2121, 1337, 21), Diagnose.alternatives(ConsoleType.PS5, DiagService.FTP))
        assertEquals(listOf(2121, 1337, 21), Diagnose.alternatives(ConsoleType.PS4, DiagService.FTP))
    }

    @Test
    fun consoleLogTriesTheOtherUsualPort() {
        assertEquals(listOf(3232, 9081), klogPorts(3232))
        assertEquals(listOf(9081, 3232), klogPorts(9081))
        assertEquals(listOf(5000), klogPorts(5000))
    }
}
