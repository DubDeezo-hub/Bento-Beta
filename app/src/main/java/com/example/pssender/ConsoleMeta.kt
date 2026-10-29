package com.example.pssender // change to match your project's package name

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ---------------------------------------------------------------------------------------------
// What games and packages say about themselves: a PS4 param.sfo, a PS5 param.json, and the header of a PS4 .pkg
// (which holds a param.sfo and an icon). Plain Kotlin, no Android, so it is tested off the phone.
// ---------------------------------------------------------------------------------------------

/** A PS4 param.sfo: keys with text or number values (TITLE, TITLE_ID, APP_VER, VERSION, CATEGORY, CONTENT_ID…). */
object Sfo {
    private const val MAGIC = 0x46535000 // "\0PSF" read little-endian

    fun parse(data: ByteArray): Map<String, Any> {
        if (data.size < 20) throw IllegalArgumentException("too short for a param.sfo")
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != MAGIC) throw IllegalArgumentException("not a param.sfo")
        val keyTable = b.getInt(8)
        val dataTable = b.getInt(12)
        val count = b.getInt(16)
        if (count !in 0..1024 || keyTable !in 20..data.size || dataTable !in 20..data.size) {
            throw IllegalArgumentException("broken param.sfo")
        }
        val out = LinkedHashMap<String, Any>()
        for (i in 0 until count) {
            val e = 20 + i * 16
            if (e + 16 > data.size) break
            val keyOff = b.getShort(e).toInt() and 0xFFFF
            val fmt = b.getShort(e + 2).toInt() and 0xFFFF
            val len = b.getInt(e + 4)
            val dataOff = b.getInt(e + 12)
            val key = cString(data, keyTable + keyOff, data.size - (keyTable + keyOff))
            val at = dataTable + dataOff
            if (key.isEmpty() || at < 0 || len < 0 || at + len > data.size) continue
            out[key] = when (fmt) {
                0x0404 -> if (len >= 4) b.getInt(at) else 0
                else -> cString(data, at, len) // 0x0204 text, 0x0004 text without its end marker
            }
        }
        return out
    }

    private fun cString(data: ByteArray, at: Int, max: Int): String {
        if (at < 0 || at >= data.size) return ""
        var end = at
        val limit = minOf(data.size, at + maxOf(max, 0))
        while (end < limit && data[end] != 0.toByte()) end++
        return String(data, at, end - at, Charsets.UTF_8).trim()
    }
}

/** What Bento shows about a game, app or package. */
data class TitleInfo(
    val titleId: String,
    val name: String,
    val version: String = "",
    /** "Game", "Update", "Add-on" (the words come from [kindRes]). */
    val category: String = "",
    val contentId: String = "",
    /** The lowest firmware it needs, e.g. "9.00", or "" when unknown. */
    val needsFirmware: String = ""
) {
    /** Which word describes [category]. */
    val kindRes: Int
        get() = when (category.lowercase()) {
            "gd", "gde", "gdc", "gda", "gdk", "gdl", "gdo" -> R.string.meta_kind_game
            "gp" -> R.string.meta_kind_patch
            "ac" -> R.string.meta_kind_addon
            "theme", "th" -> R.string.meta_kind_theme
            "" -> 0
            else -> R.string.meta_kind_other
        }
}

object TitleMeta {
    private val TITLE_ID = Regex("^[A-Z]{4}\\d{5}$")

    fun isTitleId(s: String): Boolean = TITLE_ID.matches(s)

    /** Firmware written as 0x09000000 (as in SYSTEM_VER) → "9.00". */
    fun firmwareOf(v: Long): String {
        if (v <= 0L) return ""
        val major = ((v ushr 24) and 0xFF).toString(16).toIntOrNull() ?: return ""
        val minor = ((v ushr 16) and 0xFF).toString(16).padStart(2, '0')
        return "$major.$minor"
    }

    fun fromSfo(sfo: Map<String, Any>): TitleInfo {
        fun text(k: String) = (sfo[k] as? String).orEmpty()
        val sys = (sfo["SYSTEM_VER"] as? Int)?.toLong()?.and(0xFFFFFFFFL) ?: 0L
        val category = text("CATEGORY")
        // A patch's own version is APP_VER; VERSION is the version of its data
        val version = text("APP_VER").ifEmpty { text("VERSION") }
        return TitleInfo(
            titleId = text("TITLE_ID"),
            name = text("TITLE"),
            version = version,
            category = category,
            contentId = text("CONTENT_ID"),
            needsFirmware = firmwareOf(sys)
        )
    }

    /** A PS5 param.json, in the language asked for when it has one (else its default language). */
    fun fromParamJson(text: String, language: String = ""): TitleInfo {
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("not a param.json")
        @Suppress("UNCHECKED_CAST")
        val loc = root["localizedParameters"] as? Map<String, Any?>
        fun nameIn(lang: String): String? {
            @Suppress("UNCHECKED_CAST")
            val m = loc?.get(lang) as? Map<String, Any?> ?: return null
            return (m["titleName"] as? String)?.takeIf { it.isNotBlank() }
        }
        val default = loc?.get("defaultLanguage") as? String ?: "en-US"
        val wanted = language.takeIf { it.isNotEmpty() }?.let { lang ->
            nameIn(lang) ?: loc?.keys?.firstOrNull { it.startsWith(lang.substringBefore('-') + "-") }?.let { nameIn(it) }
        }
        val name = wanted ?: nameIn(default) ?: nameIn("en-US") ?: loc?.keys?.firstNotNullOfOrNull { nameIn(it) } ?: ""
        val sys = when (val v = root["requiredSystemSoftwareVersion"]) {
            is String -> v.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L
            is Number -> v.toLong()
            else -> 0L
        }
        val cat = when ((root["applicationCategoryType"] as? Number)?.toInt()) {
            0 -> "gd"
            65536 -> "gde"
            else -> ""
        }
        return TitleInfo(
            titleId = root["titleId"] as? String ?: "",
            name = name,
            version = (root["contentVersion"] as? String ?: "").trim(),
            category = cat,
            contentId = root["contentId"] as? String ?: "",
            // The PS5 writes it as 0x0900000000000000-style: the top bytes are the firmware
            needsFirmware = firmwareOf(if (sys > 0xFFFFFFFFL) sys ushr 32 else sys)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Package files
// ---------------------------------------------------------------------------------------------

/** Reads pieces of a file that may be far away (a web link) or on the phone. */
interface RangeReader {
    /** The file's size, or -1 when it isn't known. */
    val size: Long

    /** Up to [length] bytes from [offset]. */
    fun read(offset: Long, length: Int): ByteArray
}

/** What a package says about itself. [kind] tells PS4 packages from PS5 ones and from files that aren't packages. */
class PkgInfo(
    val kind: Kind,
    val size: Long,
    val title: TitleInfo? = null,
    val icon: ByteArray? = null,
    val contentId: String = ""
) {
    enum class Kind { PS4, PS5, UNKNOWN }
}

object Pkg {
    private const val CNT = 0x7F434E54 // "\x7FCNT": a PS4 package
    private const val FIH = 0x7F464948 // "\x7FFIH": a PS5 package (its contents are encrypted)
    private const val ID_PARAM_SFO = 0x1000
    private const val ID_ICON0 = 0x1200
    private const val MAX_ENTRIES = 10_000
    private const val MAX_SFO = 64 * 1024
    private const val MAX_ICON = 2 * 1024 * 1024

    fun read(r: RangeReader): PkgInfo {
        val head = r.read(0, 0x1000)
        if (head.size < 0x80) return PkgInfo(PkgInfo.Kind.UNKNOWN, r.size)
        val b = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        return when (b.getInt(0)) {
            CNT -> readPs4(r, b, head)
            FIH -> PkgInfo(PkgInfo.Kind.PS5, r.size)
            else -> PkgInfo(PkgInfo.Kind.UNKNOWN, r.size)
        }
    }

    private fun readPs4(r: RangeReader, b: ByteBuffer, head: ByteArray): PkgInfo {
        val entries = b.getInt(0x10)
        val table = b.getInt(0x18).toLong() and 0xFFFFFFFFL
        val contentId = String(head, 0x40, 0x24, Charsets.US_ASCII).trimEnd('\u0000').trim()
        if (entries !in 1..MAX_ENTRIES) return PkgInfo(PkgInfo.Kind.PS4, r.size, contentId = contentId)
        val tableBytes = r.read(table, entries * 32)
        val t = ByteBuffer.wrap(tableBytes).order(ByteOrder.BIG_ENDIAN)
        var sfo: TitleInfo? = null
        var icon: ByteArray? = null
        for (i in 0 until minOf(entries, tableBytes.size / 32)) {
            val e = i * 32
            val id = t.getInt(e)
            val off = t.getInt(e + 16).toLong() and 0xFFFFFFFFL
            val len = t.getInt(e + 20)
            when {
                id == ID_PARAM_SFO && len in 1..MAX_SFO && sfo == null ->
                    sfo = try { TitleMeta.fromSfo(Sfo.parse(r.read(off, len))) } catch (_: Exception) { null }
                id == ID_ICON0 && len in 1..MAX_ICON && icon == null ->
                    icon = try { r.read(off, len).takeIf { it.size == len } } catch (_: Exception) { null }
            }
            if (sfo != null && icon != null) break
        }
        return PkgInfo(PkgInfo.Kind.PS4, r.size, sfo, icon, contentId)
    }
}
