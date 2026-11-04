package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateApkTest {
    private fun release(vararg names: String) = """{"tag_name":"v1.84","html_url":"https://x/tag/v1.84","assets":[""" +
        names.joinToString(",") { """{"name":"$it","browser_download_url":"https://x/download/$it"}""" } + "]}"

    @Test
    fun theAppsOwnNameIsPickedOverTheOldCopy() {
        // GitHub lists the files by name, so the old name comes first
        assertEquals("https://x/download/Crimson-Multitool-1.84.apk", parseRelease(release("Bento-1.84.apk", "Crimson-Multitool-1.84.apk"))!!.apkUrl)
        assertEquals("https://x/download/Crimson-Multitool-1.84.apk", parseRelease(release("Crimson-Multitool-1.84.apk", "Bento-1.84.apk"))!!.apkUrl)
        // A release with only one file still gives it
        assertEquals("https://x/download/Bento-1.84.apk", parseRelease(release("notes.txt", "Bento-1.84.apk"))!!.apkUrl)
    }
}
