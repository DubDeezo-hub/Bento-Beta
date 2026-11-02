package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaHenConfigTest {
    private val sample = "[Settings]\n; a comment\nFTP=1\nDPI_v2 = 0\nStartOption=1\n# another comment\nnot a setting\n"

    @Test
    fun parseKeepsOrderAndSkipsTheRest() {
        val v = EtaHenConfig.parse(sample)
        assertEquals(listOf("FTP", "DPI_v2", "StartOption"), v.keys.toList())
        assertEquals("0", v["DPI_v2"])
    }

    @Test
    fun applyChangesOnlyWhatChanged() {
        val out = EtaHenConfig.apply(sample, mapOf("DPI_v2" to "1", "FTP" to "0"))
        assertEquals("[Settings]\n; a comment\nFTP=0\nDPI_v2 = 1\nStartOption=1\n# another comment\nnot a setting\n", out)
    }

    @Test
    fun applyAddsNewKeysAtTheEnd() {
        val out = EtaHenConfig.apply("[Settings]\nFTP=1\n\n\n", mapOf("Klog" to "1"))
        assertEquals("[Settings]\nFTP=1\nKlog=1\n", out)
        assertEquals("1", EtaHenConfig.parse(out)["Klog"])
    }

    @Test
    fun windowsLineEndsStay() {
        val out = EtaHenConfig.apply("[Settings]\r\nFTP=1\r\n", mapOf("FTP" to "0", "DPI" to "1"))
        assertEquals("[Settings]\r\nFTP=0\r\nDPI=1\r\n", out)
    }

    @Test
    fun nothingChangedIsTheSameFile() {
        assertEquals(sample, EtaHenConfig.apply(sample, emptyMap()))
    }

    @Test
    fun everyKnownSettingIsThereOnce() {
        val keys = EtaHenConfig.KNOWN.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue("FTP" in keys && "DPI_v2" in keys && "PS5Debug" in keys)
        assertEquals(5, EtaHenConfig.KNOWN.first { it.key == "StartOption" }.choices.size)
    }
}
