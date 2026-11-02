package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareTest {
    @Test
    fun normalizeReadsTheUsualWaysOfWritingIt() {
        assertEquals("10.40", ConsoleFirmware.normalize("10.40"))
        assertEquals("10.40", ConsoleFirmware.normalize("10.4"))
        assertEquals("10.40", ConsoleFirmware.normalize("1040"))
        assertEquals("10.40", ConsoleFirmware.normalize(" 10,40 "))
        assertEquals("9.00", ConsoleFirmware.normalize("900"))
        assertEquals("5.05", ConsoleFirmware.normalize("5.05"))
    }

    @Test
    fun normalizeTurnsDownWhatIsNotAFirmware() {
        assertNull(ConsoleFirmware.normalize(""))
        assertNull(ConsoleFirmware.normalize("abc"))
        assertNull(ConsoleFirmware.normalize("99.00"))
        assertNull(ConsoleFirmware.normalize("0.50"))
        assertNull(ConsoleFirmware.normalize("10.40.1"))
        assertNull(ConsoleFirmware.normalize("12345"))
    }

    @Test
    fun numberIsMajorTimesHundredPlusMinor() {
        assertEquals(1040, ConsoleFirmware.number("10.40"))
        assertEquals(900, ConsoleFirmware.number("9.00"))
        assertNull(ConsoleFirmware.number("nope"))
    }

    @Test
    fun aRangeCoversEverythingInIt() {
        val c = FirmwareSupport.claims("Supported firmware: 3.00 - 10.40")
        assertEquals(FwFit.LISTED, c.fit(1040))
        assertEquals(FwFit.LISTED, c.fit(450))
        assertEquals(FwFit.NOT_LISTED, c.fit(1060))
        assertEquals("3.00", c.lowest)
        assertEquals("10.40", c.highest)
    }

    @Test
    fun aListOfVersionsIsTakenAsIs() {
        val c = FirmwareSupport.claims("Works on 3.00, 3.10, 3.20, 4.03, 4.50, 4.51, 7.61")
        assertEquals(FwFit.LISTED, c.fit(451))
        assertEquals(FwFit.NOT_LISTED, c.fit(500))
    }

    @Test
    fun aFamilyCoversItsWholeLine() {
        val c = FirmwareSupport.claims("Supports firmware 10.xx and 9.xx")
        assertEquals(FwFit.LISTED, c.fit(1040))
        assertEquals(FwFit.LISTED, c.fit(960))
        assertEquals(FwFit.NOT_LISTED, c.fit(1100))
    }

    @Test
    fun openEndedClaims() {
        val up = FirmwareSupport.claims("Supported firmware: up to 7.61 and on 4.03")
        assertEquals(FwFit.LISTED, up.fit(500))
        assertEquals(FwFit.NOT_LISTED, up.fit(800))
        val later = FirmwareSupport.claims("Tested firmware: 9.00 and later, also 8.60")
        assertEquals(FwFit.LISTED, later.fit(1360))
        assertEquals("∞", later.highest)
    }

    @Test
    fun aPageThatSaysNothingIsUnknown() {
        assertEquals(FwFit.UNKNOWN, FirmwareSupport.claims("A small FTP server.").fit(1040))
        // One stray mention is not a statement of support
        assertTrue(FirmwareSupport.claims("Decryption only on firmware 2.xx").isEmpty)
    }

    @Test
    fun linksAndNegativeLinesAreIgnored() {
        val text = """
            Not supported: 11.00 - 12.00
            See https://example.com/firmware/10.40/11.00
            Supported firmware: 4.03 - 5.50
        """.trimIndent()
        val c = FirmwareSupport.claims(text)
        assertEquals(FwFit.NOT_LISTED, c.fit(1100))
        assertEquals(FwFit.LISTED, c.fit(450))
    }

    @Test
    fun versionTagsAreNotFirmware() {
        // "v1.02" and "7.01.01" are release numbers, not firmware
        assertTrue(FirmwareSupport.claims("Release v1.02 for firmware, build 7.01.01").isEmpty)
    }
}
