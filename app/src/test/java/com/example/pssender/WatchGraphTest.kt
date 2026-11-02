package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchGraphTest {
    @Test
    fun readingsAreAddedAndOldOnesGo() {
        var h = emptyList<Double>()
        for (i in 1..70) h = withReading(h, i.toDouble())
        assertEquals(WATCH_HISTORY, h.size)
        assertEquals(70.0, h.last(), 0.0)
        assertEquals(11.0, h.first(), 0.0)
    }

    @Test
    fun notANumberIsSkipped() {
        val h = listOf(1.0)
        assertEquals(h, withReading(h, null))
        assertEquals(h, withReading(h, Double.NaN))
        assertEquals(h, withReading(h, Double.POSITIVE_INFINITY))
    }
}
