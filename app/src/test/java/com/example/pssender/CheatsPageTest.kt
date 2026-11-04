package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Test

class CheatsPageTest {
    private fun cheat(name: String, note: String = "", group: String = "") = Cheat(name, name, 0x10L, ValueType.INT, "1", note = note, group = group)

    @Test
    fun searchLooksAtNameNoteAndGroup() {
        val list = listOf(cheat("Money"), cheat("Ammo", note = "pistol only"), cheat("Speed", group = "Player"), cheat("Health", group = "Player"))
        assertEquals(list, filterCheats(list, "  "))
        assertEquals(listOf("Money"), filterCheats(list, "mon").map { it.name })
        assertEquals(listOf("Ammo"), filterCheats(list, "PISTOL").map { it.name })
        assertEquals(listOf("Speed", "Health"), filterCheats(list, "player").map { it.name })
        assertEquals(emptyList<Cheat>(), filterCheats(list, "zzz"))
    }
}
