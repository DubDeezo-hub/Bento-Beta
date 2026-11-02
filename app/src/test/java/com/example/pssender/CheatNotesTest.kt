package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CheatNotesTest {
    private val c = Cheat("id1", "Infinite health", 0x20001234L, ValueType.INT, "999", note = "Works on v1.05; breaks on v1.02")

    @Test
    fun aNoteIsKept() {
        val back = parseCheats(cheatsToJson(listOf(c))).single()
        assertEquals("Works on v1.05; breaks on v1.02", back.note)
    }

    @Test
    fun noNoteWritesNothing() {
        val json = cheatsToJson(listOf(c.copy(note = "")))
        assertFalse(json.contains("\"note\""))
        assertEquals("", parseCheats(json).single().note)
    }

    @Test
    fun oldListsWithoutNotesStillLoad() {
        val old = """[{"id":"x","name":"Gold","address":"20002000","type":"INT","value":"500"}]"""
        assertEquals("", parseCheats(old).single().note)
    }

    @Test
    fun aVeryLongNoteIsCut() {
        val back = parseCheats(cheatsToJson(listOf(c.copy(note = "a".repeat(500))))).single()
        assertEquals(CHEAT_NOTE_MAX, back.note.length)
    }
}
