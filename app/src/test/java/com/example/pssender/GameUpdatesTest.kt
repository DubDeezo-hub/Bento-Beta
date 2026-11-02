package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameUpdatesTest {
    @Test
    fun theRightSiteForEachConsole() {
        assertEquals("https://orbispatches.com", GameUpdates.siteFor("CUSA00001"))
        assertEquals("https://prosperopatches.com", GameUpdates.siteFor("PPSA01234"))
    }

    @Test
    fun keyFromEitherSitesPage() {
        assertEquals("abc123", GameUpdates.keyIn("""<div id="patches" data-key="abc123"></div>"""))
        assertEquals("deadBEEF", GameUpdates.keyIn("""fetch(url, {'titleid': 'CUSA1', 'key': 'deadBEEF'})"""))
        assertNull(GameUpdates.keyIn("<html>nothing here</html>"))
    }

    @Test
    fun patchesFromAReply() {
        val json = """
            {"success": true, "data": {"patches": [
              {"is_latest": true, "content_ver": "01.050.000", "required_firmware": "10.20.00.06", "filesize": 1048576},
              {"is_latest": false, "version": "01.020.000", "required_firmware": "07.60.00.00", "filesize": "2048"},
              {"is_latest": false, "version": "", "filesize": 1},
              {"other": {"is_latest": false, "version": "1.01", "required_firmware": "9.00"}}
            ]}}
        """.trimIndent()
        val patches = GameUpdates.parsePatches(json)
        assertEquals(3, patches.size)
        assertEquals(SitePatch("01.050.000", "10.20", 1048576), patches[0])
        assertEquals(SitePatch("01.020.000", "7.60", 2048), patches[1])
        // A PS4 title's firmware is kept as written
        assertEquals("9.00", patches[2].firmware)
    }

    @Test
    fun aFailedReplyGivesNothing() {
        assertTrue(GameUpdates.parsePatches("""{"success": false, "patches": [{"is_latest": true, "version": "1.0"}]}""").isEmpty())
        assertTrue(GameUpdates.parsePatches("[]").isEmpty())
    }

    @Test
    fun newestAndNewestThisConsoleCanTake() {
        val patches = listOf(
            SitePatch("01.020.000", "7.60", 1),
            SitePatch("01.100.000", "11.00", 3),
            SitePatch("01.050.000", "10.20", 2)
        )
        val u = GameUpdates.newest(patches, "10.40")!!
        assertEquals("01.100.000", u.version)
        assertEquals("11.00", u.needsFirmware)
        assertEquals(3L, u.size)
        assertEquals("01.050.000", u.newestForFirmware)
        // Without the console's firmware there is nothing to say about what fits
        assertEquals("", GameUpdates.newest(patches, "")!!.newestForFirmware)
        assertNull(GameUpdates.newest(emptyList(), "10.40"))
    }

    @Test
    fun aPatchWithoutAFirmwareFitsAnyConsole() {
        val u = GameUpdates.newest(listOf(SitePatch("1.10", "", 1), SitePatch("1.20", "12.00", 1)), "9.00")!!
        assertEquals("1.20", u.version)
        assertEquals("1.10", u.newestForFirmware)
    }
}
