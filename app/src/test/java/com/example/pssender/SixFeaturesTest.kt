package com.example.pssender

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SixFeaturesTest {
    @After
    fun tidy() = PayloadFirmware.reset()

    // --- Android 17 local network permission ---

    @Test
    fun localNetworkOnlyFromAndroid17() {
        assertFalse(LocalNetwork.applies(34))
        assertFalse(LocalNetwork.applies(36))
        assertTrue(LocalNetwork.applies(37))
    }

    // --- files at once: the queue's helper lanes ---

    @Test
    fun helperTakesTheFirstThatFitsAndGivesItBackFirst() {
        val q = TransferQueue<String>()
        q.add("link")
        q.add("file1")
        q.add("file2")
        val taken = q.takeFirst { it.startsWith("file") }
        assertEquals("file1", taken?.item)
        assertEquals(listOf("link", "file2"), q.waiting.value.map { it.item })
        q.putBack(taken!!)
        assertEquals(listOf("file1", "link", "file2"), q.waiting.value.map { it.item })
        assertNull(q.takeFirst { it == "nothing" })
    }

    private fun upload(name: String) = TransferRequest(name, "10.0.0.5", 2121, "anonymous", "", "/data", fileUri = "content://x/$name")

    @Test
    fun helpersLeaveTheLastOneForTheWorker() {
        val c = TransferCenter()
        c.lanes = 3
        c.enqueue(upload("a.bin"))
        assertNull(c.takeForHelper()) // only one waits: it is the worker's
        c.enqueue(upload("b.bin"))
        val h = c.takeForHelper()
        assertNotNull(h)
        assertEquals(1, c.helpersBusy)
        assertEquals(1, c.queue.waitingCount)
        assertNull(c.takeForHelper())
        // A helper that fails gives its file back, first in line
        c.helperFinished(h!!, TransferResult(false, "no"), 0L)
        assertEquals(0, c.helpersBusy)
        assertEquals(h.item.name, c.queue.waiting.value.first().item.name)
    }

    @Test
    fun noHelpersWithOneLaneOrASpeedLimitOrForLinks() {
        val c = TransferCenter()
        repeat(3) { c.enqueue(upload("f$it.bin")) }
        assertNull(c.takeForHelper()) // one lane
        c.lanes = 2
        c.setSpeedLimit(1L shl 20)
        assertNull(c.takeForHelper()) // a limit is for one connection
        c.setSpeedLimit(0L)
        assertNotNull(c.takeForHelper())
        assertFalse(fitsHelper(TransferRequest("x", "h", 21, "u", "", "/data", url = "https://example.com/x")))
    }

    @Test
    fun aDoneHelperCountsAsCompleted() {
        val c = TransferCenter()
        c.lanes = 2
        repeat(2) { c.enqueue(upload("f$it.bin")) }
        val h = c.takeForHelper()!!
        c.helperFinished(h, TransferResult(true, "ok"), 100L)
        assertEquals(1, c.info.value.completed)
        assertEquals(0, c.helpersBusy)
    }

    // --- firmware check before sending ---

    @Test
    fun firmwareClaimsKeepTheirShape() {
        val c = FwClaims(setOf(1040, 1050), listOf(403..1001))
        val back = PayloadFirmware.decode(PayloadFirmware.encode(c))!!
        assertEquals(c.exact, back.exact)
        assertEquals(c.ranges, back.ranges)
        assertNull(PayloadFirmware.decode("nonsense"))
        assertNull(PayloadFirmware.decode("e:;r:"))
    }

    @Test
    fun asksOnlyWhenThePageDoesNotNameTheFirmware() {
        PayloadFirmware.put("kstuff.elf", FwClaims(emptySet(), listOf(300..1001)))
        assertNotNull(PayloadFirmware.warning(listOf("kstuff.elf"), firmware = 1040))
        assertNull(PayloadFirmware.warning(listOf("kstuff.elf"), firmware = 900))
        assertNull(PayloadFirmware.warning(listOf("kstuff.elf"), firmware = null)) // console's firmware unknown
        assertNull(PayloadFirmware.warning(listOf("other.elf"), firmware = 1040)) // nothing kept for it
    }

    // --- stability report ---

    @Test
    fun suspectsComeFirst() {
        val rows = stabilityRows(
            loads = mapOf("etaHEN.bin" to 10, "kstuff.elf" to 4, "ftpsrv.elf" to 6),
            crashes = mapOf("kstuff.elf" to 3, "etaHEN.bin" to 3)
        )
        assertEquals("kstuff.elf", rows.first().name)
        assertTrue(rows.first().suspect)
        assertEquals(75, rows.first().percent)
        assertFalse(rows.first { it.name == "etaHEN.bin" }.suspect) // 3 of 10: not half
        assertEquals(0, rows.first { it.name == "ftpsrv.elf" }.crashes)
    }

    @Test
    fun oneCrashIsNotAPattern() {
        val r = stabilityRows(mapOf("a.elf" to 1), mapOf("a.elf" to 1)).single()
        assertFalse(r.suspect)
        // A crash counted without a load still reads sensibly
        assertEquals(2, stabilityRows(emptyMap(), mapOf("b.elf" to 2)).single().loads)
    }

    // --- finding the console by itself ---

    private fun hit(host: String, vararg s: ScanService) = ScanHit(host, emptyList(), s.toList())

    @Test
    fun offersOnlyOneClearConsole() {
        val loader = ScanService.LOADER
        assertEquals("10.0.0.9", autoPick(listOf(hit("10.0.0.9", loader)), "10.0.0.5", emptySet()))
        assertNull(autoPick(listOf(hit("10.0.0.9", loader), hit("10.0.0.10", loader)), "10.0.0.5", emptySet()))
        assertNull(autoPick(listOf(hit("10.0.0.9", ScanService.FTP)), "10.0.0.5", emptySet())) // FTP alone: any device
        assertNull(autoPick(listOf(hit("10.0.0.9", loader)), "10.0.0.5", setOf("10.0.0.9"))) // another console's
        assertNull(autoPick(listOf(hit("10.0.0.5", loader)), "10.0.0.5", emptySet())) // the saved one
    }

    // --- sharing cheats ---

    @Test
    fun cheatFileNames() {
        assertEquals("PPSA01234-cheats.json", cheatShareName("PPSA01234", "Some Game"))
        assertEquals("Some-Game-cheats.json", cheatShareName("", "Some Game!"))
        assertEquals("my-cheats.json", cheatShareName("", ""))
    }
}
