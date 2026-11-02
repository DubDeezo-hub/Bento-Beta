package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningTest {
    @Test
    fun ownPortsComeFirstAndOnlyOnce() {
        val p = Running.portsFor(ConsoleType.PS5, 12800, 9021, 1337)
        assertEquals(listOf(12800, 9021, 1337), p.take(3).map { it.port })
        assertEquals(p.size, p.map { it.port }.distinct().size)
    }

    @Test
    fun aPortTypedByHandIsLookedAt() {
        val p = Running.portsFor(ConsoleType.PS5, null, 9999, null)
        assertEquals(RunKind.LOADER, p.first { it.port == 9999 }.kind)
    }

    @Test
    fun ps4HasItsOwnPorts() {
        val ports = Running.portsFor(ConsoleType.PS4, null, null, null).map { it.port }
        assertTrue(9090 in ports)
        assertFalse(1337 in ports)
    }

    @Test
    fun nonsensePortsAreLeftOut() {
        assertFalse(Running.portsFor(ConsoleType.PS5, 0, 70000, null).any { it.port == 0 || it.port == 70000 })
    }
}
