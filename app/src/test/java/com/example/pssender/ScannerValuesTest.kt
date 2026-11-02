package com.example.pssender

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScannerValuesTest {
    @Test
    fun wholeNumbers() {
        assertArrayEquals(byteArrayOf(0xF4.toByte(), 1, 0, 0), ValueType.INT.parse("500"))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), ValueType.INT.parse("-1"))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), ValueType.INT.parse("0xFFFFFFFF"))
        assertNull(ValueType.INT.parse("0x100000000"))
        assertArrayEquals(byteArrayOf(-1), ValueType.BYTE.parse("255"))
        assertNull(ValueType.BYTE.parse("256"))
        assertNull(ValueType.SHORT.parse("70000"))
        assertNull(ValueType.INT.parse("12abc"))
        assertNull(ValueType.INT.parse(""))
        assertEquals(8, ValueType.LONG.parse("-5")!!.size)
    }

    @Test
    fun decimals() {
        assertEquals("7.5", ValueType.FLOAT.format(ValueType.FLOAT.parse("7,5")!!))
        assertEquals("100", ValueType.FLOAT.format(ValueType.FLOAT.parse("100")!!))
        assertEquals("0.1", ValueType.DOUBLE.format(ValueType.DOUBLE.parse("0.1")!!))
        assertNull(ValueType.FLOAT.parse("NaN"))
        assertNull(ValueType.FLOAT.parse("abc"))
    }

    @Test
    fun formattingReadsBack() {
        for (t in ValueType.entries) {
            val bytes = t.parse("42")!!
            assertEquals(t.name, "42", t.format(bytes))
        }
        assertEquals("255", ValueType.BYTE.format(byteArrayOf(-1)))
        assertEquals("-1", ValueType.INT.format(byteArrayOf(-1, -1, -1, -1)))
        assertEquals("?", ValueType.INT.format(byteArrayOf(1, 2), 0))
    }

    @Test
    fun namesThatAreNotKnownFallBack() {
        assertEquals(ValueType.INT, ValueType.of("nope"))
        assertEquals(ScanCompare.EXACT, ScanCompare.of(null))
        assertEquals(ValueType.FLOAT, ValueType.of("FLOAT"))
    }

    @Test
    fun aSignatureSkipsTheValueAndPointers() {
        val maps = listOf(DbgMap("heap", 0x20000000L, 0x20010000L, 0, 3))
        val window = ByteArray(Signature.BEFORE + 4 + Signature.AFTER) { (it + 1).toByte() }
        // An 8-byte pointer into the heap right before the value
        val p = 0x20001234L
        val at = 8
        for (k in 0 until 8) window[at + k] = (p ushr (8 * k)).toByte()
        val sig = Signature.make(0x30000018L, 4, window, maps)!!
        assertEquals(Signature.BEFORE, sig.valueAt)
        for (i in Signature.BEFORE until Signature.BEFORE + 4) assertEquals(0, sig.mask[i].toInt())
        for (i in at until at + 8) assertEquals(0, sig.mask[i].toInt())
        assertEquals(1, sig.mask[0].toInt())
        // Saved and read back
        val again = Signature.parse(sig.patternHex(), sig.maskText(), sig.valueAt)
        assertNotNull(again)
        assertArrayEquals(sig.mask, again!!.mask)
        assertNull(Signature.parse("0102", "1", 0))
    }

    @Test
    fun aSignatureOfZerosIsNoUse() {
        assertNull(Signature.make(0x1000L, 4, ByteArray(Signature.BEFORE + 4 + Signature.AFTER), emptyList()))
    }
}
