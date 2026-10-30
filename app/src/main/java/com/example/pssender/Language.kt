package com.example.pssender // change to match your project's package name

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// The app's own language, picked inside the app. On Android 13 and newer it is the system's per-app language (the
// same setting as in the phone's settings); on older phones the app keeps the choice and applies it itself.
// ---------------------------------------------------------------------------------------------

object AppLanguage {
    /** The languages the app is translated into (the same list as res/xml/locales_config.xml). */
    val TAGS = listOf(
        "en", "es", "fr", "de", "it", "pt-BR", "ru", "ja", "ko", "zh-CN", "zh-TW", "ar", "tr", "nl", "pl", "sv", "nb",
        "da", "fi", "cs", "hu", "ro", "el", "uk", "bg", "hr", "he", "th", "vi", "id", "pt-PT", "sk", "ca", "hi", "bn",
        "ur", "fa", "ms", "fil", "sl", "sr", "lt", "lv", "et", "sw", "af", "ta", "te", "mr", "gu", "kn", "ml", "pa"
    )

    private const val KEY = "app_language"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The chosen language's tag, or "" when the app follows the phone. */
    fun current(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = try { ctx.getSystemService(LocaleManager::class.java)?.applicationLocales } catch (_: Exception) { null }
            return if (list == null || list.isEmpty) "" else match(list[0].toLanguageTag())
        }
        return prefs(ctx).getString(KEY, "") ?: ""
    }

    /** The tag in [TAGS] for [tag] ("pt-BR" for "pt-BR", "he" for "iw", "zh-TW" for "zh-Hant-TW"), else [tag]. */
    fun match(tag: String): String {
        if (tag in TAGS) return tag
        val l = Locale.forLanguageTag(tag)
        val lang = when (l.language) { "iw" -> "he"; "in" -> "id"; else -> l.language }
        val region = l.country.ifEmpty { if (l.script == "Hant") "TW" else if (l.script == "Hans") "CN" else "" }
        return TAGS.firstOrNull { it == "$lang-$region" } ?: TAGS.firstOrNull { it == lang || it.startsWith("$lang-") } ?: tag
    }

    /** Switches the app to [tag] ("" for the phone's language) and redraws [activity] in it. */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system keeps it and redraws the app itself
            val lm = activity.getSystemService(LocaleManager::class.java) ?: return
            lm.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        prefs(activity).edit().putString(KEY, tag).commit()
        // The texts outside the screens (notifications, messages) read through the app; point them at the new language
        AppText.init(activity, wrap(activity.applicationContext, force = true))
        activity.recreate()
    }

    /**
     * [base] in the chosen language, for Android 12 and older (newer versions do it themselves). The app and the
     * activity pass their context through this when they start.
     */
    fun wrap(base: Context, force: Boolean = false): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString(KEY, "") ?: ""
        val locale = if (tag.isEmpty()) {
            if (!force) return base
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            Locale.forLanguageTag(tag)
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** A language's name in that language, like "Español" or "日本語". */
    fun nameOf(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        val name = l.getDisplayName(l)
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
    }
}

/** Picks the app's language: the phone's, or any of the ones it is translated into, each named in its own language. */
@Composable
fun LanguageDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val now = remember { AppLanguage.current(ctx) }
    val names = remember { AppLanguage.TAGS.map { it to AppLanguage.nameOf(it) }.sortedBy { it.second.lowercase() } }
    val pick = { tag: String ->
        onClose()
        if (tag != now) findActivity(ctx)?.let { AppLanguage.set(it, tag) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.language_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ListRow(str(R.string.language_phone), str(R.string.language_phone_detail), highlight = now.isEmpty(), enabled = true) { pick("") }
                names.forEach { (tag, name) ->
                    ListRow(name, tag, highlight = now == tag, enabled = true) { pick(tag) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}
