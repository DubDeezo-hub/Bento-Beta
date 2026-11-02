package com.example.pssender

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @After
    fun forget() = Hints.clear()

    @Test
    fun linksInText() {
        val text = "Get it here (https://example.com/a.pkg). Also <http://host/b.pkg>, and https://x.com/c_(1).pkg!"
        assertEquals(listOf("https://example.com/a.pkg", "http://host/b.pkg", "https://x.com/c_(1).pkg"), linksIn(text))
        assertTrue(linksIn("no links here").isEmpty())
        assertEquals(1, linksIn("https://a.com/x https://a.com/x").size)
        assertEquals(LINKS_MAX, linksIn((1..80).joinToString(" ") { "https://a.com/$it" }).size)
        assertFalse(isWebLink("https://"))
        assertTrue(isWebLink("http://10.0.2.2:12800/sample.pkg"))
    }

    @Test
    fun theBoxKeepsWhatWasNotSent() {
        val box = "https://a.com/1.pkg https://a.com/2.pkg\nnote\nhttps://a.com/3.pkg"
        assertEquals("https://a.com/2.pkg\nnote", removeLinks(box, setOf("https://a.com/1.pkg", "https://a.com/3.pkg")))
        assertEquals(listOf("note"), skippedWords("https://a.com/1.pkg note"))
        assertEquals("https://a.com/1.pkg\nhttps://a.com/2.pkg", mergeLinks("https://a.com/1.pkg", listOf("https://a.com/1.pkg", "https://a.com/2.pkg")))
        assertEquals("https://a.com/1.pkg", mergeLinks("", listOf("https://a.com/1.pkg")))
    }

    @Test
    fun shortNamesForButtons() {
        assertEquals("My Game.pkg", linkLabel("https://host/files/My%20Game.pkg?token=1"))
        assertEquals("host.com", linkLabel("https://host.com/"))
        assertEquals(10, linkLabel("https://h/" + "a".repeat(40), max = 10).length)
    }

    @Test
    fun recentLinks() {
        assertEquals(listOf("c", "a", "b"), pushRecent(listOf("a", "b", "c"), "c"))
        assertEquals(2, pushRecent(listOf("a", "b"), "c", max = 2).size)
    }

    @Test
    fun aBatchGoesOnAfterARefusalButNotWhenTheConsoleIsGone() = runBlocking {
        val gone = "The console can't be reached right now (test)"
        Hints.tag(gone, HelpTopic.UNREACHABLE)
        val links = listOf("l1", "l2", "l3", "l4")
        val accepted = ArrayList<String>()
        val b = sendInstallBatch(links, send = { l ->
            when (l) {
                "l2" -> InstallReply(false, "not a package")
                "l3" -> InstallReply(false, gone)
                else -> InstallReply(true, "queued")
            }
        }, onAccepted = { accepted.add(it) })
        assertEquals(listOf("l1"), b.accepted)
        assertEquals(listOf("l2", "l3"), b.failed.map { it.first })
        assertEquals(listOf("l4"), b.notSent)
        assertEquals(listOf("l1"), accepted)
        // One link: the installer's own words
        val one = sendInstallBatch(listOf("x"), send = { InstallReply(true, "SUCCESS: queued") })
        assertEquals("SUCCESS: queued", batchMessage(one, 1))
    }
}
