package com.example.pssender // change to match your project's package name

// ---------------------------------------------------------------------------------------------
// A package checked before installing, set against what the console already has: is the game there, is this an
// update to it, is it already installed, does it need newer firmware than the console runs.
// ---------------------------------------------------------------------------------------------

/** What one line of the comparison says, and whether it is good news, a warning or just information. */
enum class PkgNoteLevel { GOOD, INFO, WARN }

/** One line under a checked package: a string resource and its arguments. */
data class PkgNote(val level: PkgNoteLevel, val res: Int, val args: List<String> = emptyList())

object PkgCheck {
    /** The title ID inside a content ID ("UP9000-CUSA00001_00-…" holds CUSA00001), or "". */
    fun titleIdOf(contentId: String): String {
        val id = contentId.trim()
        if (id.length < 16 || id[6] != '-') return ""
        val t = id.substring(7, 16)
        return if (TITLE_ID.matches(t)) t else ""
    }

    private val TITLE_ID = Regex("""^[A-Z]{4}\d{5}$""")

    /** -1, 0 or 1 as version [a] is older than, the same as, or newer than [b] ("01.02" against "1.10"). */
    fun compareVersions(a: String, b: String): Int {
        val x = a.trim().split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * The lines for a package against the console. [titleId], [version] and [category] are the package's ("gd" a
     * game, "gp" an update, "ac" an add-on); [installed] is the console's entry for the same title, or null when it
     * does not have it. [pkgIsPs5] and [consoleIsPs5] tell the systems apart; [needsFirmware] and [consoleFirmware]
     * (like "9.00") are compared only for a PS4 package going to a PS4, where they mean the same thing.
     */
    fun notes(
        titleId: String,
        version: String,
        category: String,
        installed: AppEntry?,
        pkgIsPs5: Boolean,
        consoleIsPs5: Boolean,
        needsFirmware: String,
        consoleFirmware: String
    ): List<PkgNote> {
        val out = ArrayList<PkgNote>()
        if (pkgIsPs5 && !consoleIsPs5) {
            out.add(PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_wrong_console))
            return out
        }
        if (!pkgIsPs5 && !consoleIsPs5) {
            val need = ConsoleFirmware.number(needsFirmware)
            val have = ConsoleFirmware.number(consoleFirmware)
            if (need != null && have != null && need > have) {
                out.add(PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_needs_fw, listOf(needsFirmware, consoleFirmware)))
            }
        }
        if (titleId.isEmpty()) return out
        val have = installed?.info?.version.orEmpty()
        val name = installed?.shownName ?: titleId
        when (category.lowercase()) {
            "gp" -> when {
                installed == null -> out.add(PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_patch_no_game, listOf(titleId)))
                have.isEmpty() || version.isEmpty() -> out.add(PkgNote(PkgNoteLevel.INFO, R.string.meta_installed_patch_for, listOf(name)))
                compareVersions(version, have) > 0 -> out.add(PkgNote(PkgNoteLevel.GOOD, R.string.meta_installed_update, listOf(have, version)))
                compareVersions(version, have) == 0 -> out.add(PkgNote(PkgNoteLevel.INFO, R.string.meta_installed_same, listOf(have)))
                else -> out.add(PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_older, listOf(have, version)))
            }
            "ac" -> out.add(
                if (installed == null) PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_addon_no_game, listOf(titleId))
                else PkgNote(PkgNoteLevel.GOOD, R.string.meta_installed_addon_for, listOf(name))
            )
            else -> when {
                installed == null -> out.add(PkgNote(PkgNoteLevel.GOOD, R.string.meta_installed_not_yet))
                have.isEmpty() || version.isEmpty() -> out.add(PkgNote(PkgNoteLevel.INFO, R.string.meta_installed_have))
                compareVersions(version, have) == 0 -> out.add(PkgNote(PkgNoteLevel.INFO, R.string.meta_installed_same, listOf(have)))
                compareVersions(version, have) < 0 -> out.add(PkgNote(PkgNoteLevel.WARN, R.string.meta_installed_older, listOf(have, version)))
                else -> out.add(PkgNote(PkgNoteLevel.GOOD, R.string.meta_installed_update, listOf(have, version)))
            }
        }
        return out
    }
}
