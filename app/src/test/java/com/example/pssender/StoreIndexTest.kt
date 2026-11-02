package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoreIndexTest {
    private fun entry(repo: String, name: String = repo.substringAfter('/'), file: String = "a.elf", hidden: Boolean = false) =
        StoreEntry(repo = repo, name = name, about = 0, console = ConsoleType.PS5, file = file, hidden = hidden)

    @Test
    fun repoOfReadsGithubAddresses() {
        assertEquals("owner/repo", StoreIndex.repoOf("https://github.com/owner/repo"))
        assertEquals("owner/repo", StoreIndex.repoOf("https://github.com/owner/repo/releases/latest"))
        assertEquals("owner/repo", StoreIndex.repoOf("https://github.com/owner/repo.git"))
        assertEquals("", StoreIndex.repoOf("https://gitlab.com/owner/repo"))
    }

    @Test
    fun curatedEntriesAndTheirLanguages() {
        val json = """
            {"payloads": [
              {"repo": "a/one", "name": "One", "console": "ps5", "file": "one.elf", "anyFirmware": true,
               "about": {"en": "English", "de": "Deutsch"}},
              {"repo": "b/two", "name": "Two", "console": "PS4", "about": "Plain"},
              {"repo": "c/three", "name": "Three", "console": "xbox"},
              {"repo": "nope", "name": "Bad repo", "console": "ps5"},
              {"repo": "d/four", "name": "", "console": "ps5"},
              {"repo": "e/five", "name": "Five", "console": "ps5", "hidden": true}
            ]}
        """.trimIndent()
        val de = StoreIndex.parseCurated(json, "de")
        assertEquals(listOf("a/one", "b/two", "e/five"), de.map { it.repo })
        assertEquals("Deutsch", de[0].aboutText)
        assertTrue(de[0].anyFirmware)
        assertEquals(ConsoleType.PS4, de[1].console)
        assertEquals("Plain", de[1].aboutText)
        assertTrue(de[2].hidden)
        // A language the list doesn't have falls back to English
        assertEquals("English", StoreIndex.parseCurated(json, "fr")[0].aboutText)
    }

    @Test
    fun curatedListThatIsNotJsonGivesNothing() {
        assertTrue(StoreIndex.parseCurated("[]", "en").isEmpty())
        assertTrue(StoreIndex.parseCurated("{\"payloads\": 5}", "en").isEmpty())
    }

    @Test
    fun aReplyThatIsNotJsonGivesAnEmptyListInsteadOfAnError() {
        // A Wi-Fi login page or a download cut short must not stop the store (or the app) from opening
        val page = "<html><body>Please log in to the Wi-Fi</body></html>"
        assertTrue(StoreIndex.parseCurated(page, "en").isEmpty())
        assertTrue(StoreIndex.parseCommunity(page).isEmpty())
        assertTrue(StoreIndex.parseSearch(page).isEmpty())
        assertTrue(StoreIndex.parseCurated("{\"payloads\": [{\"repo\": ", "en").isEmpty())
    }

    @Test
    fun theRealListInTheRepositoryReads() {
        // payloads/index.json is read by every installed copy of the app: it must stay readable
        val file = listOf(File("../payloads/index.json"), File("payloads/index.json")).first { it.exists() }
        val entries = StoreIndex.parseCurated(file.readText(), "en")
        assertTrue("the list should have entries", entries.isNotEmpty())
        entries.forEach { assertTrue("${it.repo} needs a file", it.file.isNotEmpty() || it.hidden) }
        assertEquals("every repo once", entries.size, entries.map { it.repo.lowercase() }.toSet().size)
    }

    @Test
    fun mergeChangesAddsAndHides() {
        val builtIn = listOf(entry("a/one", "One", "one.elf"), entry("b/two", "Two", "two.elf"), entry("c/three"))
        val curated = listOf(
            entry("A/One", "One (new name)", ""),
            entry("b/two", hidden = true),
            entry("d/four", "Four", "four.elf"),
            entry("e/five", hidden = true)
        )
        val merged = StoreIndex.merge(builtIn, curated)
        assertEquals(listOf("a/one", "c/three", "d/four"), merged.map { it.repo })
        assertEquals("One (new name)", merged[0].name)
        // An empty file name in the list keeps the built-in one
        assertEquals("one.elf", merged[0].file)
    }

    @Test
    fun communityListInBothShapes() {
        val item = """{"name": " Tool ", "source": "https://github.com/x/tool", "description": "Does things",
            "version": "1.2", "last_update": "2026-10-01T12:00:00Z", "url": "https://mirror/tool.elf",
            "checksum": "ABCDEF", "filename": "tool.elf"}"""
        val asList = StoreIndex.parseCommunity("[$item, {\"name\": \"\"}]")
        val asObject = StoreIndex.parseCommunity("{\"payloads\": [$item]}")
        assertEquals(1, asList.size)
        assertEquals(asList, asObject)
        val p = asList[0]
        assertEquals("Tool", p.name)
        assertEquals("x/tool", p.repo)
        assertEquals("x/tool", p.key)
        assertEquals("2026-10-01", p.updated)
        assertEquals("abcdef", p.sha256)
        assertEquals("tool.elf", p.fileName)
    }

    @Test
    fun communityEntryWithoutGithubHasItsOwnKey() {
        val p = StoreIndex.parseCommunity("""[{"name": "Elsewhere", "source": "https://example.com/x"}]""")[0]
        assertEquals("", p.repo)
        assertEquals("list:elsewhere", p.key)
        assertFalse(p.key.isEmpty())
    }
}
