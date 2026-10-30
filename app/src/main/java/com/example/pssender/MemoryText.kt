package com.example.pssender // change to match your project's package name

// ---------------------------------------------------------------------------------------------
// Text search in a game's memory: a word (a character's name, an item, a line of dialogue) as UTF-8 and as UTF-16,
// the two ways games keep text. The search itself runs on the console; this file encodes the words, cuts memory
// into pieces that overlap by the word's length (so nothing at a piece's edge is missed) and turns the bytes around
// each match into readable text.
// ---------------------------------------------------------------------------------------------

/** How text is stored. */
enum class TextEncoding(val label: String, val unit: Int) {
    UTF8("UTF-8", 1),
    UTF16("UTF-16", 2);

    fun encode(text: String): ByteArray = when (this) {
        UTF8 -> text.toByteArray(Charsets.UTF_8)
        UTF16 -> text.toByteArray(Charsets.UTF_16LE)
    }

    fun decode(bytes: ByteArray): String = when (this) {
        UTF8 -> String(bytes, Charsets.UTF_8)
        UTF16 -> String(bytes, 0, bytes.size - bytes.size % 2, Charsets.UTF_16LE)
    }

    /** The end of a string: one zero byte, or two. */
    val terminator: ByteArray get() = ByteArray(unit)
}

/** A place the text was found, with what comes just before and after it. */
data class TextHit(
    val address: Long,
    val encoding: TextEncoding,
    /** The match's length in bytes. */
    val length: Int,
    val before: String = "",
    val after: String = "",
    /** What was written over the match, once it has been changed. */
    val written: String? = null
)

object TextSearch {
    /** No more matches than this are kept (the rest are counted). */
    const val MAX_HITS = 1000

    /** Bytes read before and after each match for its preview. */
    const val CONTEXT = 32

    /** Pieces stay under the size the debugger takes in one search. */
    private const val PIECE = 0x40000000L

    /** Words shorter than this (in bytes) would match nearly everywhere. */
    const val MIN_BYTES = 3

    /** [text] as bytes in each of [encodings], with every byte required to match. */
    fun patterns(text: String, encodings: Collection<TextEncoding>): List<Pair<TextEncoding, ByteArray>> =
        if (text.isEmpty()) emptyList() else encodings.map { it to it.encode(text) }

    /** [maps] cut into (start, length) pieces that overlap by [overlap] bytes, so a match across a cut is still found. */
    fun pieces(maps: List<DbgMap>, overlap: Int): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        val back = overlap.coerceAtLeast(0).toLong()
        for (m in maps.sortedBy { it.start }) {
            var a = m.start
            while (a < m.end) {
                val len = minOf(PIECE, m.end - a)
                out.add(a to len.toInt())
                if (a + len >= m.end) break
                a += len - back
            }
        }
        return out
    }

    /**
     * The readable text in [bytes] as [enc]: anything that is not a printable character shows as "·". With [fromEnd]
     * the text is read backwards from the end up to the start of its string (a zero); otherwise up to its end.
     */
    fun readable(bytes: ByteArray, enc: TextEncoding, fromEnd: Boolean): String {
        val unit = enc.unit
        val usable = bytes.size - bytes.size % unit
        // Cut at the string's end (or, read backwards, after the last zero before the match)
        var from = 0
        var to = usable
        var i = 0
        if (fromEnd) {
            i = usable - unit
            while (i >= 0) {
                if (isZero(bytes, i, unit)) { from = i + unit; break }
                i -= unit
            }
        } else {
            while (i < usable) {
                if (isZero(bytes, i, unit)) { to = i; break }
                i += unit
            }
        }
        if (from >= to) return ""
        val text = enc.decode(bytes.copyOfRange(from, to))
        return buildString {
            for (c in text) append(if (c == '�' || Character.isISOControl(c)) '·' else c)
        }.let { if (fromEnd) it.takeLast(CONTEXT) else it.take(CONTEXT) }
    }

    private fun isZero(b: ByteArray, at: Int, unit: Int): Boolean {
        for (k in 0 until unit) if (b[at + k].toInt() != 0) return false
        return true
    }

    /**
     * The bytes to write to replace a [oldLength]-byte match with [text]: the new text, then zeros up to the old
     * length (so the rest of the old word does not show). Null when the new text is longer than the old one (it would
     * write over whatever the game keeps after it).
     */
    fun replacement(text: String, enc: TextEncoding, oldLength: Int): ByteArray? {
        val b = enc.encode(text)
        if (b.size > oldLength) return null
        return b.copyOf(oldLength)
    }
}
