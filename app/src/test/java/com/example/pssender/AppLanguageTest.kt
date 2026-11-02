package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AppLanguageTest {
    @Test
    fun exactTagsStay() {
        assertEquals("pt-BR", AppLanguage.match("pt-BR"))
        assertEquals("de", AppLanguage.match("de"))
    }

    @Test
    fun androidsOldCodesAndRegions() {
        assertEquals("he", AppLanguage.match("iw"))
        assertEquals("he", AppLanguage.match("iw-IL"))
        assertEquals("id", AppLanguage.match("in"))
        assertEquals("de", AppLanguage.match("de-AT"))
        assertEquals("pt-PT", AppLanguage.match("pt-PT"))
    }

    @Test
    fun chineseScripts() {
        assertEquals("zh-TW", AppLanguage.match("zh-Hant-TW"))
        assertEquals("zh-TW", AppLanguage.match("zh-Hant"))
        assertEquals("zh-CN", AppLanguage.match("zh-Hans"))
        assertEquals("zh-CN", AppLanguage.match("zh-CN"))
    }

    @Test
    fun unknownLanguagesAreLeftAlone() {
        assertEquals("xx", AppLanguage.match("xx"))
    }

    @Test
    fun theListMatchesTheLanguageSettings() {
        // The in-app picker and Android's per-app language list (res/xml/locales_config.xml) must offer the same
        val file = listOf(File("src/main/res/xml/locales_config.xml"), File("app/src/main/res/xml/locales_config.xml")).first { it.exists() }
        val inXml = Regex("""android:name="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toList()
        assertEquals(inXml, AppLanguage.TAGS)
        assertEquals(AppLanguage.TAGS.size, AppLanguage.TAGS.toSet().size)
    }
}
