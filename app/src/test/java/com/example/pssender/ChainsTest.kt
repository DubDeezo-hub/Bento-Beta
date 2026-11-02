package com.example.pssender

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainsTest {
    private val closed = "Nothing answers on the loader port of the console (test)"

    @After
    fun forget() = Hints.clear()

    private fun chain(id: String, name: String = id, vararg payloads: String) =
        Chain(id, name, payloads.map { ChainStep(it, 2) })

    @Test
    fun pausesAndMissingPayloads() {
        val c = Chain("a", "A", listOf(ChainStep("x.elf", 5), ChainStep("y.elf", 10), ChainStep("x.elf", 99)))
        // The pause after the last payload isn't used
        assertEquals(15, chainPauseSeconds(c))
        assertEquals(listOf("y.elf"), missingPayloads(c, setOf("x.elf")))
        assertEquals(0, parseWait(""))
        assertEquals(12, parseWait("12 s"))
        assertEquals(CHAIN_MAX_WAIT, parseWait("99999"))
    }

    @Test
    fun savedChainsReadBackAndBrokenOnesAreLeftOut() {
        val list = listOf(chain("a", "First", "x.elf", "y.elf"), chain("b", "Second", "z.elf"))
        assertEquals(list, parseChains(chainsToJson(list)))
        val messy = """[{"id": "a", "name": "  A  ", "steps": [{"payload": "x", "wait": 9999}, {"payload": ""}]},
            {"id": "a", "name": "again", "steps": [{"payload": "x"}]},
            {"id": "", "name": "no id", "steps": [{"payload": "x"}]},
            {"id": "c", "name": "no steps", "steps": []}, 5]"""
        val read = parseChains(messy)
        assertEquals(1, read.size)
        assertEquals("A", read[0].name)
        assertEquals(listOf(ChainStep("x", CHAIN_MAX_WAIT)), read[0].steps)
        assertTrue(parseChains("not json").isEmpty())
        assertTrue(parseChains(null).isEmpty())
    }

    @Test
    fun mergingReplacesByIdAndKeepsTheRest() {
        val phone = listOf(chain("a", "A", "1"), chain("b", "B", "2"))
        val file = listOf(chain("b", "B new", "3"), chain("c", "C", "4"))
        assertEquals(listOf("A", "B new", "C"), mergeChains(phone, file).map { it.name })
    }

    @Test
    fun aChainRunsInOrderWithItsPauses() = runBlocking {
        val sent = ArrayList<String>()
        var slept = 0
        val o = runChain(
            listOf(ChainStep("a", 2), ChainStep("b", 3), ChainStep("c", 7)),
            send = { sent.add(it); PayloadReply(true, "ok") },
            sleepSecond = { slept++ }
        )
        assertEquals(listOf("a", "b", "c"), sent)
        assertEquals(5, slept)
        assertTrue(o.ok)
        assertEquals(3, o.sent)
    }

    @Test
    fun aFailedPayloadEndsTheChain() = runBlocking {
        val o = runChain(
            listOf(ChainStep("a"), ChainStep("b"), ChainStep("c")),
            send = { PayloadReply(it != "b", if (it == "b") "refused" else "ok") },
            sleepSecond = {}, retrySeconds = 0
        )
        assertEquals(2, o.failedStep)
        assertEquals("b", o.failedPayload)
        assertEquals("refused", o.failure)
        assertEquals(1, o.sent)
    }

    @Test
    fun theNextPayloadWaitsForTheLoaderToComeUp() = runBlocking {
        Hints.tag(closed, HelpTopic.PORT_CLOSED)
        var tries = 0
        var now = 0L
        val o = runChain(
            listOf(ChainStep("a"), ChainStep("b")),
            send = { if (it == "b" && ++tries < 3) PayloadReply(false, closed) else PayloadReply(true, "ok") },
            sleepSecond = { now += 1000 },
            clock = { now }
        )
        assertTrue(o.ok)
        assertEquals(3, tries)
    }

    @Test
    fun theFirstPayloadIsNotRetried() = runBlocking {
        Hints.tag(closed, HelpTopic.PORT_CLOSED)
        var tries = 0
        val o = runChain(listOf(ChainStep("a")), send = { tries++; PayloadReply(false, closed) }, sleepSecond = {})
        assertEquals(1, tries)
        assertEquals(1, o.failedStep)
    }

    @Test
    fun stoppingDuringAPause() = runBlocking {
        var stop = false
        val o = runChain(
            listOf(ChainStep("a", 10), ChainStep("b")),
            send = { PayloadReply(true, "ok") },
            sleepSecond = { stop = true },
            cancelled = { stop }
        )
        assertTrue(o.stopped)
        assertEquals(1, o.sent)
    }

    @Test
    fun theAutoChainRunsOnceWhenTheLoaderComesUp() {
        val id = "console-" + System.nanoTime()
        assertEquals(false, AutoChain.due(id, null, true, now = 1_000_000))
        assertEquals(false, AutoChain.due(id, true, true, now = 1_000_000))
        assertEquals(true, AutoChain.due(id, false, true, now = 1_000_000))
        // Not again within ten minutes
        assertEquals(false, AutoChain.due(id, false, true, now = 1_000_000 + 60_000))
        assertEquals(true, AutoChain.due(id, false, true, now = 1_000_000 + 11 * 60_000))
    }
}
