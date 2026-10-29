package com.example.pssender // change to match your project's package name

// ---------------------------------------------------------------------------------------------
// Ready-made cheats: the community cheat files that etaHEN and GoldHEN use (github.com/etaHEN/PS5_Cheats), found by
// the game's title ID and version. Each file lists mods ("Infinite Health", ...), and each mod is a few patches:
// at an offset from where the game's eboot.bin is loaded, write the "on" bytes to turn it on and the "off" bytes to
// turn it off. No Android parts here, so the parsing can be tested against the real files on a computer.
// ---------------------------------------------------------------------------------------------

/** One patch of a mod. [section] is set in some PS4 files and means a different base; those mods are not offered. */
class CheatPatch(val offset: Long, val on: ByteArray, val off: ByteArray, val section: Int?)

/** One mod of a cheat file. A [button] is applied once; the others are switched on and off. */
class CheatMod(val name: String, val button: Boolean, val hint: String, val patches: List<CheatPatch>) {
    val supported: Boolean get() = patches.isNotEmpty() && patches.all { it.section == null && it.on.isNotEmpty() }
}

/** A cheat file. [master] patches are needed before any mod works (a few files have them). */
class CheatFile(
    val name: String,
    val titleId: String,
    val version: String,
    val process: String,
    val mods: List<CheatMod>,
    val master: List<CheatPatch>,
    val credits: List<String>
)

/** An entry of the repository's list: the file name and the game's name. */
class CheatIndexEntry(val file: String, val game: String) {
    val titleId: String get() = file.substringBefore('_')
    val version: String get() = CheatFiles.versionOf(file)
}

object CheatFiles {
    /** Where the files are read from; a different one can be set (the fake console serves its own). */
    const val DEFAULT_SOURCE = "https://raw.githubusercontent.com/etaHEN/PS5_Cheats/main/"

    /** "json.txt": one "file=game name" line per cheat file. */
    fun parseIndex(text: String): List<CheatIndexEntry> = text.lineSequence()
        .map { it.trim().removePrefix("﻿") }
        .filter { it.contains('=') && it.substringBefore('=').endsWith(".json", ignoreCase = true) }
        .map { CheatIndexEntry(it.substringBefore('=').trim(), it.substringAfter('=').trim()) }
        .toList()

    /** "01.026.000" from "PPSA01628_01.026.000.json", and "01.00" from "CUSA00016_01.00_2.json". */
    fun versionOf(file: String): String {
        val core = file.substringAfter('_', "").removeSuffix(".json").removeSuffix(".JSON")
        val parts = core.split('_')
        return parts.first()
    }

    /** Versions compared by their numbers, so "1.026.000" and "01.026.000" are the same, and so are "01.00" and "1.0". */
    fun sameVersion(a: String, b: String): Boolean {
        fun nums(v: String) = v.trim().split('.').map { it.trimStart('0').ifEmpty { "0" } }.dropLastWhile { it == "0" }
        return a.isNotBlank() && b.isNotBlank() && nums(a) == nums(b)
    }

    /** The files for [titleId], the ones for [gameVersion] first. */
    fun forGame(index: List<CheatIndexEntry>, titleId: String, gameVersion: String): List<CheatIndexEntry> =
        index.filter { it.titleId.equals(titleId, ignoreCase = true) }
            .sortedWith(compareBy<CheatIndexEntry>({ if (sameVersion(it.version, gameVersion)) 0 else 1 }, { it.file }))

    /** Hex text ("90 90 90", "909090", "0x90") as bytes, or null. */
    fun hex(text: String): ByteArray? {
        val t = text.replace(" ", "").replace("0x", "").replace("0X", "")
        if (t.length % 2 != 0) return null
        return try {
            ByteArray(t.length / 2) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun patches(list: Any?): List<CheatPatch> = (list as? List<*>).orEmpty().mapNotNull { e ->
        val m = e as? Map<*, *> ?: return@mapNotNull null
        val off = (m["offset"] as? String)?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return@mapNotNull null
        val offset = off.toLongOrNull(16) ?: return@mapNotNull null
        val on = hex(m["on"]?.toString() ?: "") ?: return@mapNotNull null
        val offBytes = hex(m["off"]?.toString() ?: "") ?: ByteArray(0)
        val section = m["section"]?.toString()?.trim()?.toIntOrNull()
        CheatPatch(offset, on, offBytes, section)
    }

    /** A cheat file's JSON. Throws on text that is not one. */
    fun parse(text: String): CheatFile {
        val root = MiniJson.parse(text.removePrefix("﻿")) as? Map<*, *> ?: throw IllegalArgumentException("not a cheat file")
        val mods = (root["mods"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            CheatMod(
                name = m["name"]?.toString()?.trim().orEmpty().ifBlank { "?" },
                button = m["type"]?.toString().equals("button", ignoreCase = true),
                hint = (m["hint"] as? String).orEmpty(),
                patches = patches(m["memory"])
            )
        }
        val master = (root["master"] as? Map<*, *>)?.let { patches(it["memory"]) }.orEmpty()
        return CheatFile(
            name = root["name"]?.toString().orEmpty(),
            titleId = root["id"]?.toString().orEmpty(),
            version = root["version"]?.toString().orEmpty(),
            process = root["process"]?.toString().orEmpty().ifBlank { "eboot.bin" },
            mods = mods,
            master = master,
            credits = (root["credits"] as? List<*>).orEmpty().mapNotNull { it?.toString() }
        )
    }

    /** The load address of the game: the lowest region named like its process, else [fallback]. */
    // --- writing a cheat file (Bento's own cheats, exported) ---

    private fun jsonText(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun hexBytes(b: ByteArray): String = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /** One switchable mod writing [on] at [offset] from the eboot's start (and [off] back when switched off). */
    fun modJson(name: String, offset: Long, on: ByteArray, off: ByteArray): String =
        "    {\n" +
            "      \"name\": ${jsonText(name)},\n" +
            "      \"hint\": null,\n" +
            "      \"type\": \"checkbox\",\n" +
            "      \"memory\": [\n" +
            "        {\n" +
            "          \"offset\": \"${java.lang.Long.toHexString(offset)}\",\n" +
            "          \"on\": \"${hexBytes(on)}\",\n" +
            "          \"off\": \"${hexBytes(off)}\"\n" +
            "        }\n" +
            "      ]\n" +
            "    }"

    /** A whole cheat file in the PS5_Cheats layout, with the [mods] made by [modJson]. */
    fun fileJson(game: String, titleId: String, version: String, process: String, mods: List<String>): String =
        "{\n" +
            "  \"name\": ${jsonText(game.ifBlank { titleId })},\n" +
            "  \"id\": ${jsonText(titleId)},\n" +
            "  \"version\": ${jsonText(version.trim())},\n" +
            "  \"process\": ${jsonText(process)},\n" +
            "  \"mods\": [\n" + mods.joinToString(",\n") + "\n  ],\n" +
            "  \"credits\": [\n    \"Made with Bento\"\n  ]\n" +
            "}\n"

    /** "PPSA01234_01.000.json": the name the cheat folders use. */
    fun fileName(titleId: String, version: String): String =
        titleId.ifBlank { "UNKNOWN" } + "_" + version.trim().ifBlank { "00.00" } + ".json"

    fun baseOf(maps: List<DbgMap>, process: String, fallback: Long): Long =
        maps.filter { it.name.contains(process.substringBefore('.'), ignoreCase = true) }.minOfOrNull { it.start } ?: fallback
}

/** Just enough JSON for cheat files: objects (as maps), arrays (as lists), strings, numbers, true, false, null. */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.space()
        if (p.i < text.length) throw IllegalArgumentException("text after the JSON at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            space()
            if (i >= s.length) throw IllegalArgumentException("JSON ends early")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("bad JSON at $i")
            i += w.length
            return v
        }

        private fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            if (t.isEmpty()) throw IllegalArgumentException("bad JSON at $start")
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("unterminated string")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("bad escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            space()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                space()
                if (i >= s.length) throw IllegalArgumentException("array ends early")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("bad array at ${i - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                space()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("bad object key at $i")
                val k = str()
                space()
                if (i >= s.length || s[i++] != ':') throw IllegalArgumentException("missing : at ${i - 1}")
                out[k] = value()
                space()
                if (i >= s.length) throw IllegalArgumentException("object ends early")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("bad object at ${i - 1}")
                }
            }
        }
    }
}
