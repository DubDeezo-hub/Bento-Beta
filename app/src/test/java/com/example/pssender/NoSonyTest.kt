package com.example.pssender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Crimson Multitool's first rule: nothing the phone does, and nothing it tells the console to do, may reach a Sony server (no
 * updates pushed, no bans). This fails the build if a Sony address ever turns up in the app's code, its texts or
 * its payload list.
 */
class NoSonyTest {
    private val sony = Regex("""playstation\.(net|com)|sony\.(com|net|co\.jp)|sonyentertainmentnetwork|\bsie\.com|\bscea\.com""", RegexOption.IGNORE_CASE)

    private fun root(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun noSonyAddressInTheApp() {
        val main = root()
        val files = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "json", "txt") }.toMutableList()
        listOf(File(main, "../../../payloads/index.json"), File("payloads/index.json")).firstOrNull { it.exists() }?.let { files.add(it) }
        assertTrue("no files were looked at", files.size > 50)
        val found = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (sony.containsMatchIn(line)) "${f.path}:${i + 1}: ${line.trim().take(120)}" else null }
        }
        assertTrue("Sony addresses found:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theUpdateCheckOnlyAsksThePatchSites() {
        assertTrue(GameUpdates.siteFor("CUSA00001").startsWith("https://orbispatches.com"))
        assertTrue(GameUpdates.siteFor("PPSA00001").startsWith("https://prosperopatches.com"))
    }
}
