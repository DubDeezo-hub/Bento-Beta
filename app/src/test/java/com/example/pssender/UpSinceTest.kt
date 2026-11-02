package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpSinceTest {
    private val t = 1_000_000_000L
    private val min = 60_000L

    @Test
    fun seenComingUpIsExact() {
        val s = UpSince.next(null, was = false, up = true, now = t)!!
        assertEquals(t, s.since)
        assertTrue(s.exact)
    }

    @Test
    fun foundAlreadyUpIsAtLeast() {
        val s = UpSince.next(null, was = null, up = true, now = t)!!
        assertFalse(s.exact)
    }

    @Test
    fun stillUpKeepsTheStart() {
        val first = UpSince.next(null, was = false, up = true, now = t)!!
        val later = UpSince.next(first, was = true, up = true, now = t + 5 * min)!!
        assertEquals(t, later.since)
        assertTrue(later.exact)
    }

    @Test
    fun reopeningSoonKeepsTheStart() {
        // The app was closed for 10 minutes: nothing can have restarted the console unseen for long
        val first = UpState(t, true, t)
        val back = UpSince.next(first, was = null, up = true, now = t + 10 * min)!!
        assertEquals(t, back.since)
    }

    @Test
    fun aLongGapStartsOver() {
        // Closed for hours: the console may have been restarted in between, so it is only "at least" from now
        val first = UpState(t, true, t)
        val back = UpSince.next(first, was = null, up = true, now = t + 300 * min)!!
        assertEquals(t + 300 * min, back.since)
        assertFalse(back.exact)
    }

    @Test
    fun downForgets() {
        assertNull(UpSince.next(UpState(t, true, t), was = true, up = false, now = t + min))
    }
}
