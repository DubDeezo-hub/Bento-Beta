package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CheatPowerTest {
    private fun int(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun float(v: Float) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()

    // --- freeze rules ---

    @Test
    fun neverBelowOnlyWritesWhenItDrops() {
        val limit = int(50)
        assertTrue(ruleWrites(FreezeRule.AT_LEAST, ValueType.INT, int(30), limit))
        assertFalse(ruleWrites(FreezeRule.AT_LEAST, ValueType.INT, int(80), limit)) // healed above: left alone
        assertFalse(ruleWrites(FreezeRule.AT_LEAST, ValueType.INT, int(50), limit))
    }

    @Test
    fun neverAboveOnlyWritesWhenItRises() {
        val limit = float(10f)
        assertTrue(ruleWrites(FreezeRule.AT_MOST, ValueType.FLOAT, float(12.5f), limit))
        assertFalse(ruleWrites(FreezeRule.AT_MOST, ValueType.FLOAT, float(3f), limit))
    }

    @Test
    fun exactAlwaysWritesAndUnreadableNever() {
        assertTrue(ruleWrites(FreezeRule.EXACT, ValueType.INT, null, int(1)))
        assertFalse(ruleWrites(FreezeRule.AT_LEAST, ValueType.INT, null, int(1)))
        assertFalse(ruleWrites(FreezeRule.AT_LEAST, ValueType.INT, ByteArray(2), int(1))) // too short to be a value
    }

    @Test
    fun bytesAreUnsignedAndNumbersReadRight() {
        assertEquals(200.0, numberOf(ValueType.BYTE, byteArrayOf(200.toByte()))!!, 0.0)
        assertEquals(-5.0, numberOf(ValueType.INT, int(-5))!!, 0.0)
        assertNull(numberOf(ValueType.FLOAT, float(Float.NaN)))
    }

    @Test
    fun aRuleIsSavedAndReadBack() {
        val c = Cheat("id1", "Health", 0x1000L, ValueType.INT, "50", rule = FreezeRule.AT_LEAST)
        val back = parseCheats(cheatsToJson(listOf(c))).single()
        assertEquals(FreezeRule.AT_LEAST, back.rule)
        // Lists from before the rules read as "hold exactly"
        assertEquals(FreezeRule.EXACT, parseCheats("""[{"id":"x","name":"n","address":"10","type":"INT","value":"1"}]""").single().rule)
    }

    // --- pointer chosen for a new cheat ---

    @Test
    fun theShortestNearestPathIsChosen() {
        val deep = PointerPath(0x100, listOf(0x10L, 0x20L, 0x30L))
        val far = PointerPath(0x200, listOf(0x900L))
        val near = PointerPath(0x300, listOf(0x18L))
        assertEquals(near, bestPointer(listOf(deep, far, near)))
        assertNull(bestPointer(emptyList()))
    }

    // --- related values ---

    @Test
    fun theSameNumberNearbyComesFirst() {
        // 16 bytes each way around a health of 100: max health 100 at +8, armor 40 at -4, a big address-like number, zeros
        val span = 16
        val buf = ByteBuffer.allocate(span * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(span - 4, 40)
        buf.putInt(span, 100)
        buf.putInt(span + 8, 100)
        buf.putInt(span + 12, 2_000_000_000)
        val list = relatedValues(ValueType.INT, buf.array(), span, 100.0)
        assertEquals(8, list.first().offset)
        assertEquals(RelatedWhy.SAME, list.first().why)
        assertEquals(RelatedWhy.SIMILAR, list.first { it.offset == -4 }.why)
        assertTrue(list.none { it.offset == 12 }) // too big to be a game number
        assertTrue(list.none { it.offset == 0 }) // not the value itself
    }

    @Test
    fun plausibleNumbers() {
        assertFalse(plausibleGameNumber(ValueType.INT, 0.0))
        assertTrue(plausibleGameNumber(ValueType.INT, 250.0))
        assertFalse(plausibleGameNumber(ValueType.FLOAT, 1e-12))
        assertTrue(plausibleGameNumber(ValueType.FLOAT, 0.75))
    }

    // --- quick toggles, game updates, new helper kinds ---

    @Test
    fun quickTogglesPutFrozenFirstAndShowTwo() {
        val a = Cheat("a", "A", 1L, ValueType.INT, "1")
        val b = Cheat("b", "B", 2L, ValueType.INT, "1", frozen = true)
        val c = Cheat("c", "C", 3L, ValueType.INT, "1")
        assertEquals(listOf("b", "a"), quickCheats(listOf(a, b, c)).map { it.id })
    }

    @Test
    fun aGameUpdateIsNoticed() {
        assertTrue(versionMoved("01.000", "01.002"))
        assertFalse(versionMoved("01.002", "01.002"))
        assertFalse(versionMoved("", "01.002")) // first time: nothing to compare with
    }

    @Test
    fun switchesAreBytesAndTimersDecimals() {
        assertEquals(ValueType.BYTE, helperStartType(HelperKind.SWITCH, decimals = false))
        assertEquals(ValueType.FLOAT, helperStartType(HelperKind.TIMER, decimals = false))
    }
}
