package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun headingsLinksAndMarksGo() {
        val md = "## What's new\r\n\r\n* Fixed **kstuff** loading\n- See [the wiki](https://example.org/wiki)\n\n![shot](https://example.org/a.png)\n`code` here"
        val t = plainReleaseNotes(md)
        assertEquals("What's new\n\n• Fixed kstuff loading\n• See the wiki\n\ncode here", t)
    }

    @Test
    fun commentsAndDividersGo() {
        val t = plainReleaseNotes("Line one\n<!-- hidden -->\n---\nLine two")
        assertFalse(t.contains("hidden"))
        assertFalse(t.contains("---"))
        assertTrue(t.startsWith("Line one"))
        assertTrue(t.endsWith("Line two"))
    }

    @Test
    fun longNotesAreCut() {
        val t = plainReleaseNotes("a".repeat(5000), max = 100)
        assertEquals(101, t.length)
        assertTrue(t.endsWith("…"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", plainReleaseNotes("  \n\n<!-- only a comment -->\n"))
    }

    @Test
    fun manyBlankLinesBecomeOne() {
        assertEquals("a\n\nb", plainReleaseNotes("a\n\n\n\n\nb"))
    }
}
