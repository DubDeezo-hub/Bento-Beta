package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSizesTest {
    @Test
    fun groupsByWhatIsDirectlyInside() {
        val files = mapOf(
            "games/a.pkg" to 700L, "games/deep/b.pkg" to 300L,
            "logs/x.txt" to 5L,
            "readme.txt" to 10L, "notes.txt" to 2L
        )
        val rows = sizesByChild(files, setOf("games", "games/deep", "logs", "empty"))
        assertEquals(listOf("games", "", "logs", "empty"), rows.map { it.name })
        assertEquals(1000L, rows[0].bytes)
        assertEquals(2, rows[0].files)
        assertEquals(12L, rows[1].bytes) // the loose files
        assertEquals(0L, rows[3].bytes)  // an empty folder still shows
    }

    @Test
    fun unknownSizesCountAsNothing() {
        val rows = sizesByChild(mapOf("a/b" to -1L), emptySet())
        assertEquals(0L, rows.single().bytes)
    }

    @Test
    fun nothingGivesNothing() {
        assertEquals(emptyList<SizeRow>(), sizesByChild(emptyMap(), emptySet()))
    }
}
