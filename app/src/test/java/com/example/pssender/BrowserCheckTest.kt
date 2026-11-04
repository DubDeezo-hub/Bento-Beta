package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCheckTest {
    private val webView = "Mozilla/5.0 (Linux; Android 15; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36"

    @Test
    fun theBrowserSaysWhatChromeSays() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            phoneUserAgent(webView)
        )
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            desktopUserAgent(webView)
        )
    }

    @Test
    fun aBrowserCheckIsNotTheSiteBeingDown() {
        assertTrue(isBotCheck(403, mapOf("cf-mitigated" to "challenge")))
        assertTrue(isBotCheck(503, mapOf("Server" to "cloudflare")))
        assertTrue(isBotCheck(429, mapOf("server" to "ddos-guard")))
        assertFalse(isBotCheck(503, mapOf("Server" to "nginx")))
        assertFalse(isBotCheck(404, mapOf("Server" to "cloudflare")))
        assertFalse(isBotCheck(503, null))
    }
}
