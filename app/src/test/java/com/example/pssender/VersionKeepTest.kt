package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VersionKeepTest {
    private val a = Chain("a", "Jailbreak", listOf(ChainStep("kstuff.elf", 10), ChainStep("etaHEN-2.6.bin")))
    private val b = Chain("b", "Tools", listOf(ChainStep("ftpsrv.elf")))

    @Test
    fun chainsFollowARename() {
        val out = chainsWithPayloadRenamed(listOf(a, b), "etaHEN-2.6.bin", "etaHEN-2.5B.bin")
        assertEquals(listOf("kstuff.elf", "etaHEN-2.5B.bin"), out[0].steps.map { it.payload })
        assertEquals(10, out[0].steps[0].waitSeconds) // pauses stay
        assertSame(b, out[1]) // untouched chains stay as they were
    }

    @Test
    fun nothingToRenameChangesNothing() {
        val list = listOf(a, b)
        assertEquals(list, chainsWithPayloadRenamed(list, "other.elf", "x.elf"))
    }

    @Test
    fun keyBecomesOneSafeFolderName() {
        assertEquals("EchoStretch_kstuff", previousFolderName("EchoStretch/kstuff"))
        assertEquals("_", previousFolderName(""))
        assertEquals("a.b-c_d", previousFolderName("a.b-c_d"))
    }
}
