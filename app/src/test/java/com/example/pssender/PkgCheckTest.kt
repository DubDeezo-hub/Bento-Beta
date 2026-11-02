package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkgCheckTest {
    private fun installed(version: String = "01.000", name: String = "My Game") =
        AppEntry("CUSA00001", TitleInfo(titleId = "CUSA00001", name = name, version = version))

    private fun notes(
        category: String, version: String, have: AppEntry?, pkgIsPs5: Boolean = false, consoleIsPs5: Boolean = false,
        needs: String = "", fw: String = "", titleId: String = "CUSA00001"
    ) = PkgCheck.notes(titleId, version, category, have, pkgIsPs5, consoleIsPs5, needs, fw)

    @Test
    fun versionsCompareAsNumbers() {
        assertEquals(0, PkgCheck.compareVersions("01.02", "1.2"))
        assertEquals(-1, PkgCheck.compareVersions("1.02", "1.10"))
        assertEquals(1, PkgCheck.compareVersions("1.10", "1.9"))
        assertEquals(1, PkgCheck.compareVersions("1.0.1", "1.0"))
        assertEquals(0, PkgCheck.compareVersions("1.0", "1.0.0"))
        assertEquals(-1, PkgCheck.compareVersions("01.000.000", "01.050.000"))
        // Dashes and underscores separate parts too
        assertEquals(1, PkgCheck.compareVersions("1_10", "1_9"))
        assertEquals(-1, PkgCheck.compareVersions("2.0-3", "2.0-12"))
    }

    @Test
    fun titleIdFromContentId() {
        assertEquals("CUSA00001", PkgCheck.titleIdOf("UP9000-CUSA00001_00-ABCDEFGHIJKLMNOP"))
        assertEquals("PPSA01234", PkgCheck.titleIdOf("EP0001-PPSA01234_00-XXXXXXXXXXXXXXXX"))
        assertEquals("", PkgCheck.titleIdOf("short"))
        assertEquals("", PkgCheck.titleIdOf("UP9000_CUSA00001_00"))
        assertEquals("", PkgCheck.titleIdOf("UP9000-cusa00001_00-X"))
    }

    @Test
    fun aPs5PackageOnAPs4IsOnlyAWarning() {
        val n = notes("gd", "1.00", null, pkgIsPs5 = true, consoleIsPs5 = false)
        assertEquals(listOf(PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_wrong_console)), n)
    }

    @Test
    fun firmwareIsComparedOnlyPs4ToPs4() {
        val ps4 = notes("gd", "1.00", null, needs = "11.00", fw = "9.00")
        assertEquals(PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_needs_fw, listOf("11.00", "9.00")), ps4[0])
        val ps5 = notes("gd", "1.00", null, pkgIsPs5 = true, consoleIsPs5 = true, needs = "11.00", fw = "9.00")
        assertTrue(ps5.none { it.res == R.string.meta_installed_needs_fw })
    }

    @Test
    fun aGame() {
        assertEquals(R.string.meta_installed_not_yet, notes("gd", "1.00", null).single().res)
        assertEquals(PkgNoteLevel.GOOD, notes("gd", "01.050", installed("01.000")).single().level)
        assertEquals(R.string.meta_installed_same, notes("gd", "1.0", installed("01.000")).single().res)
        assertEquals(PkgNoteLevel.WARN, notes("gd", "0.90", installed("01.000")).single().level)
        assertEquals(R.string.meta_installed_have, notes("gd", "", installed("01.000")).single().res)
    }

    @Test
    fun anUpdate() {
        assertEquals(PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_patch_no_game, listOf("CUSA00001")), notes("gp", "1.10", null).single())
        assertEquals(PkgNote(PkgNoteLevel.GOOD, R.string.meta_installed_update, listOf("01.000", "1.10")), notes("GP", "1.10", installed()).single())
        assertEquals(R.string.meta_installed_older, notes("gp", "0.50", installed()).single().res)
        assertEquals(PkgNote(PkgNoteLevel.INFO, R.string.meta_installed_patch_for, listOf("My Game")), notes("gp", "1.10", installed("")).single())
    }

    @Test
    fun anAddOn() {
        assertEquals(PkgNoteLevel.WARN, notes("ac", "", null).single().level)
        assertEquals(PkgNote(PkgNoteLevel.GOOD, R.string.meta_installed_addon_for, listOf("My Game")), notes("ac", "", installed()).single())
        // Without a name the title ID stands in
        assertEquals(listOf("CUSA00001"), notes("ac", "", installed(name = " ")).single().args)
    }

    @Test
    fun withoutATitleIdOnlyTheFirmwareIsChecked() {
        assertTrue(notes("gd", "1.00", installed(), titleId = "").isEmpty())
    }
}
