package com.example.pssender
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
class ChainOrderTest {
    private val ps5 = ConsoleType.PS5
    private val ps4 = ConsoleType.PS4
    @Test fun goodOrderIsFine() {
        assertNull(ChainOrder.check(listOf("kstuff-lite_v1.11.elf","ShadowMountPlus.elf","etaHEN-2.5B.bin"), ps5))
        assertNull(ChainOrder.check(listOf("ftpsrv.elf","ps5debug.elf"), ps5))
        assertNull(ChainOrder.check(emptyList(), ps5))
    }
    @Test fun etahenBeforeKstuffWarns() {
        assertNotNull(ChainOrder.check(listOf("etaHEN-2.5B.bin","kstuff.elf"), ps5))
    }
    @Test fun shadowBeforeKstuffWarns() {
        assertNotNull(ChainOrder.check(listOf("ShadowMountPlus_1.7.elf","kstuff.elf"), ps5))
    }
    @Test fun elfldrWithEtahenWarns() {
        assertNotNull(ChainOrder.check(listOf("kstuff.elf","elfldr.elf","etaHEN.bin"), ps5))
    }
    @Test fun sameKindTwiceWarns() {
        assertNotNull(ChainOrder.check(listOf("etaHEN.bin","etaHEN-again.bin"), ps5))
        // also on PS4 (GoldHEN twice)
        assertNotNull(ChainOrder.check(listOf("goldhen.bin","goldhen2.bin"), ps4))
    }
    @Test fun ps4IgnoresPs5OrderRules() {
        // elfldr + etaHEN naming on a PS4 profile: order rules are PS5-only, so no warning
        assertNull(ChainOrder.check(listOf("etaHEN.bin","elfldr.elf"), ps4))
    }
}
