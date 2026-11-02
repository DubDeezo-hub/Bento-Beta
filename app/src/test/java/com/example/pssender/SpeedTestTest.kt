package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestTest {
    @Test
    fun megabytesPerSecond() {
        assertEquals(16.0, mbPerSecond(16L shl 20, 1000), 0.0001)
        assertEquals(8.0, mbPerSecond(16L shl 20, 2000), 0.0001)
    }

    @Test
    fun noTimeIsNoSpeed() {
        assertEquals(0.0, mbPerSecond(1000, 0), 0.0)
    }
}
