package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCTest {
    // --- Crash bisect ---

    @Test
    fun bisectFindsTheCrashingPayload() {
        val all = listOf("kstuff.elf", "a.elf", "b.elf", "c.elf", "d.elf")
        var s = bisectStart(all, listOf("kstuff.elf"))
        assertEquals(listOf("a.elf", "b.elf", "c.elf", "d.elf"), s.suspects)
        assertEquals(listOf("kstuff.elf", "a.elf", "b.elf"), s.send)
        assertEquals(2, s.roundsLeft)
        // c is the bad one: a+b fine, then c crashes
        s = bisectAnswer(s, crashed = false)
        assertEquals(listOf("c.elf", "d.elf"), s.suspects)
        assertEquals(2, s.round)
        assertEquals(listOf("kstuff.elf", "c.elf"), s.send)
        s = bisectAnswer(s, crashed = true)
        assertTrue(s.done)
        assertEquals("c.elf", s.culprit)
        // a finished search does not change
        assertEquals(s, bisectAnswer(s, crashed = false))
    }

    @Test
    fun bisectClearsWhenNoSuspectCrashes() {
        var s = bisectStart(listOf("kstuff.elf", "a.elf"), listOf("kstuff.elf"))
        assertEquals(listOf("a.elf"), s.testing)
        assertNull(s.culprit)
        s = bisectAnswer(s, crashed = false)
        assertTrue(s.cleared)
        assertNull(s.culprit)
        assertTrue(bisectStart(listOf("kstuff.elf"), listOf("kstuff.elf")).cleared)
    }

    @Test
    fun bisectKeepsTheChainsOrder() {
        val s = bisectStart(listOf("a.elf", "kstuff.elf", "b.elf", "c.elf"), listOf("kstuff.elf"))
        assertEquals(listOf("a.elf", "kstuff.elf", "b.elf"), s.send)
    }

    // --- Crash explainer and dev report ---

    @Test
    fun explainerReadsTheLog() {
        assertEquals(CrashCause.FIRMWARE, explainCrash(listOf("error: firmware not supported"), emptyList()).first())
        assertTrue(CrashCause.PANIC in explainCrash(listOf("kernel panic: trap 12"), emptyList()))
        assertTrue(CrashCause.FAULT in explainCrash(listOf("page fault at 0x1234"), emptyList()))
        assertTrue(CrashCause.MEMORY in explainCrash(listOf("out of memory"), emptyList()))
        assertTrue(CrashCause.TWICE in explainCrash(listOf("ok"), listOf("etaHEN-2.5B.bin", "etaHEN-2.5B.bin")))
        assertEquals(listOf(CrashCause.SUDDEN), explainCrash(emptyList(), listOf("a.elf")))
        assertEquals(listOf(CrashCause.UNKNOWN), explainCrash(listOf("loading game"), listOf("a.elf")))
    }

    @Test
    fun crashNoteSplits() {
        val sent = listOf(SendLog.Send(1_000L, "h", "kstuff.elf"), SendLog.Send(2_000L, "h", "etaHEN.bin"))
        val note = CrashDiary.format("192.168.1.2", "PS5", listOf("line one", "line two"), sent, java.util.Date(3_000L))
        val p = parseCrashNote(note)
        assertEquals(listOf("kstuff.elf", "etaHEN.bin"), p.sent)
        assertEquals(listOf("line one", "line two"), p.lines.filter { it.isNotBlank() })
        assertEquals(emptyList<String>(), parseCrashNote(CrashDiary.format("h", "", emptyList(), emptyList(), java.util.Date())).sent)
        assertEquals(emptyList<String>(), parseCrashNote("nothing here").sent)
    }

    @Test
    fun reportHasNoAddresses() {
        assertEquals("from x.x.x.x:9021", withoutAddresses("from 192.168.1.20:9021"))
        val body = devReportBody("etaHEN.bin", "2.5B", "PS5", "10.40", listOf("kstuff.elf", "etaHEN.bin"), listOf("connected to 10.0.0.5", "panic"))
        assertFalse(body.contains("10.0.0.5"))
        assertTrue(body.contains("kstuff.elf → etaHEN.bin"))
        assertTrue(body.contains("firmware 10.40"))
        assertTrue(devReportBody("x", "", "PS5", "", emptyList(), List(500) { "y".repeat(300) }).length <= 6000)
    }

    // --- Update-block check ---

    @Test
    fun countsUpdateRules() {
        val text = "# block updates\nupdate.example.net=0.0.0.0\n; updates too\nother.example=0.0.0.0\nfwupdate.example=0.0.0.0 # update\n"
        assertEquals(2, countUpdateRules(text))
        assertEquals(0, countUpdateRules(""))
        assertTrue(looksLikeDnsSettings("nanodns.ini"))
        assertTrue(looksLikeDnsSettings("hosts"))
        assertFalse(looksLikeDnsSettings("nanodns.elf"))
        assertFalse(UpdateBlockResult("", 0).found)
        assertTrue(UpdateBlockResult("/data/nanodns/hosts.txt", 3).blocked)
    }

    // --- Load-order recipes ---

    @Test
    fun recipesPickTheBestOrderFromTheShelf() {
        assertEquals("kstuff>etahen", recipeKey(listOf("kstuff-v1.2.elf", "etaHEN-2.5B.bin")))
        val json = """{"recipes":{"PS5 10.40":{"kstuff>etahen":{"worked":30,"crashed":2},"etahen":{"worked":5,"crashed":9},"kstuff>shadowmount>etahen":{"worked":40,"crashed":1}}}}"""
        val r = parseRecipes(json)
        assertEquals(30, r["PS5 10.40"]!!["kstuff>etahen"]!!.worked)
        val shelf = listOf("kstuff-v1.2.elf", "etaHEN-2.5B.bin")
        val best = bestRecipe(r, "PS5 10.40", shelf)!!
        assertEquals(listOf("kstuff-v1.2.elf", "etaHEN-2.5B.bin"), best.first)
        val more = bestRecipe(r, "PS5 10.40", shelf + "shadowmount.elf")!!
        assertEquals(listOf("kstuff-v1.2.elf", "shadowmount.elf", "etaHEN-2.5B.bin"), more.first)
        assertNull(bestRecipe(r, "PS5 9.60", shelf))
        assertTrue(parseRecipes("not json").isEmpty())
    }

    // --- Shared cheat library ---

    @Test
    fun sharedListsRoundTrip() {
        val cheats = listOf(
            Cheat("1", "Money", 0x1A2B3CL, ValueType.INT, "99999", frozen = true, sigHex = "aabb"),
            Cheat("2", "Health", 0x400L, ValueType.FLOAT, "100", group = "Player")
        )
        val arr = sharedCheatsJson(cheats)
        assertFalse(arr.contains("aabb"))
        val json = """{"lists":[{"id":1,"game":"Game","version":"01.000","cheats":$arr,"works":1,"broken":3},""" +
            """{"id":2,"game":"Game","version":"01.010","cheats":$arr,"works":5,"broken":0},{"id":3,"cheats":[]}]}"""
        val lists = parseSharedLists(json)
        assertEquals(listOf(2, 1), lists.map { it.id })
        val back = lists.first().cheats
        assertEquals(0x1A2B3CL, back[0].address)
        assertEquals(ValueType.FLOAT, back[1].type)
        assertEquals("Player", back[1].group)
        assertFalse(back[0].frozen)
        assertTrue(shareCheatsBody("CUSA00001", "Game", "01.000", cheats).startsWith("<!-- crimson-cheats -->"))
        assertTrue(parseSharedLists(null).isEmpty())
    }
}
