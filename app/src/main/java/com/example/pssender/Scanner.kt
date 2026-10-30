package com.example.pssender

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ---------------------------------------------------------------------------------------------
// The memory scanner behind the Memory tab, Cheat Engine style: a first scan finds every place in the game's
// writable memory that holds a value, and each next scan keeps only the places that still fit (the value changed,
// went down, is now 93, ...). It runs on ps5debug-NG's SCAN_START / SCAN_COUNT commands (see Ps5Debug.kt), so the
// console does the comparing and only the matches come over the network. No Android parts: it can be tested alone.
// ---------------------------------------------------------------------------------------------

/** The kinds of value the scanner can look for. [wire] is ps5debug's number for it; [size] is how many bytes it takes. */
enum class ValueType(val wire: Int, val size: Int, val label: String, val isFloat: Boolean = false, val signed: Boolean = true) {
    BYTE(0, 1, "1 byte", signed = false),
    SHORT(3, 2, "2 bytes"),
    INT(5, 4, "4 bytes"),
    LONG(7, 8, "8 bytes"),
    FLOAT(8, 4, "Float", isFloat = true),
    DOUBLE(9, 8, "Double", isFloat = true);

    /** The value typed by the user (decimal, or hex with 0x; a decimal point for the float types) as bytes, or null. */
    fun parse(text: String): ByteArray? {
        val t = text.trim().replace(",", ".")
        if (t.isEmpty()) return null
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        if (isFloat) {
            val d = t.toDoubleOrNull() ?: return null
            if (d.isNaN() || d.isInfinite()) return null
            if (this == FLOAT) b.putFloat(d.toFloat()) else b.putDouble(d)
            return b.array()
        }
        val neg = t.startsWith("-")
        val digits = t.removePrefix("-").removePrefix("+")
        val hex = digits.startsWith("0x", ignoreCase = true)
        val n: Long = try {
            if (hex) java.lang.Long.parseUnsignedLong(digits.substring(2), 16) else digits.toLong()
        } catch (_: NumberFormatException) {
            return null
        }
        val v = if (neg) -n else n
        when (this) {
            BYTE -> if (v !in -128L..255L) return null else b.put(v.toByte())
            SHORT -> if (v !in -32768L..65535L) return null else b.putShort(v.toShort())
            INT -> if (v !in Int.MIN_VALUE.toLong()..0xFFFFFFFFL) return null else b.putInt(v.toInt())
            else -> b.putLong(v)
        }
        return b.array()
    }

    /** The value at [at] in [buf] as text. */
    fun format(buf: ByteArray, at: Int = 0): String {
        if (at + size > buf.size) return "?"
        val b = ByteBuffer.wrap(buf, at, size).order(ByteOrder.LITTLE_ENDIAN)
        return when (this) {
            BYTE -> (b.get().toInt() and 0xFF).toString()
            SHORT -> b.short.toString()
            INT -> b.int.toString()
            LONG -> b.long.toString()
            FLOAT -> trimFloat(b.float.toDouble(), 7)
            DOUBLE -> trimFloat(b.double, 12)
        }
    }

    companion object {
        fun of(name: String?): ValueType = entries.firstOrNull { it.name == name } ?: INT

        private fun trimFloat(d: Double, digits: Int): String {
            if (d.isNaN() || d.isInfinite()) return d.toString()
            val s = "%.${digits}g".format(java.util.Locale.ROOT, d)
            if (!s.contains('.') || s.contains('e')) return s
            return s.trimEnd('0').trimEnd('.')
        }
    }
}

/** How a scan compares. [wire] is ps5debug's number; [first] says whether it can start a search; [values] how many numbers it needs. */
enum class ScanCompare(val wire: Int, val label: String, val first: Boolean, val values: Int) {
    EXACT(0, "Exact value", true, 1),
    BIGGER(2, "Bigger than", true, 1),
    SMALLER(3, "Smaller than", true, 1),
    BETWEEN(4, "Between", true, 2),
    UNKNOWN(11, "Unknown value", true, 0),
    INCREASED(5, "Increased", false, 0),
    INCREASED_BY(6, "Increased by", false, 1),
    DECREASED(7, "Decreased", false, 0),
    DECREASED_BY(8, "Decreased by", false, 1),
    CHANGED(9, "Changed", false, 0),
    UNCHANGED(10, "Unchanged", false, 0);

    companion object {
        fun of(name: String?): ScanCompare = entries.firstOrNull { it.name == name } ?: EXACT
    }
}

/** A stretch of memory scanned as one piece. Offsets in the results are 32-bit, so a piece is at most 2 GB. */
class ScanSegment(val base: Long, val length: Int) {
    var count = 0
        private set
    var offsets = IntArray(0)
        private set
    var values = ByteArray(0)
        private set

    fun add(offset: Int, buf: ByteArray, at: Int, size: Int) {
        if (count == offsets.size) {
            val grow = maxOf(64, offsets.size * 2)
            offsets = offsets.copyOf(grow)
            values = values.copyOf(grow * size)
        }
        offsets[count] = offset
        System.arraycopy(buf, at, values, count * size, size)
        count++
    }

    /** Index of [offset] in this segment's (ascending) results, or -1. */
    fun indexOf(offset: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val o = offsets[mid]
            when {
                o < offset -> lo = mid + 1
                o > offset -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }
}

/** One match to show in the list. [previous] is its value at the scan before, when there was one. */
class MemoryHit(val address: Long, val value: ByteArray, val previous: ByteArray?)

/** The scan was stopped because it found more than [limit] matches. */
class TooManyResults(val limit: Int) : Exception("more than $limit matches")

/** Everything a search keeps between scans. */
class ScanResults(val pid: Int, val type: ValueType, val segments: List<ScanSegment>, val scans: Int) {
    val total: Long = segments.sumOf { it.count.toLong() }

    /** The first [limit] matches, with their value at the scan before when [before] is given. */
    fun hits(limit: Int, before: ScanResults? = null): List<MemoryHit> {
        val out = ArrayList<MemoryHit>()
        for (seg in segments) {
            val old = before?.segments?.firstOrNull { it.base == seg.base }
            for (i in 0 until seg.count) {
                if (out.size >= limit) return out
                val v = seg.values.copyOfRange(i * type.size, (i + 1) * type.size)
                val j = old?.indexOf(seg.offsets[i]) ?: -1
                val p = if (old != null && j >= 0) old.values.copyOfRange(j * type.size, (j + 1) * type.size) else null
                out.add(MemoryHit(seg.base + seg.offsets[i], v, p))
            }
        }
        return out
    }
}

object Scanner {
    /** At most this many matches are kept; a first scan that finds more stops and asks for a more unusual value. */
    const val MAX_RESULTS = 3_000_000

    /** The biggest piece scanned in one request (offsets are 32-bit and kept positive). */
    private const val MAX_SEGMENT = 0x40000000L

    /** The regions worth searching: readable and writable, the way a game keeps its health, money and so on. */
    fun searchable(maps: List<DbgMap>): List<DbgMap> = maps.filter { it.readable && it.writable && it.size > 0 }

    /** Regions this big and this slow (graphics memory, mostly) take ages to scan and hardly ever hold game values. */
    const val SLOW_MIN_BYTES = 64L shl 20
    const val SLOW_MBPS = 100

    /** [maps] without the big slow regions [speeds] reports (uncached, or slower than [SLOW_MBPS]). */
    fun withoutSlow(maps: List<DbgMap>, speeds: List<DbgRegionSpeed>): List<DbgMap> = maps.filter { m ->
        val sp = speeds.firstOrNull { it.start == m.start } ?: return@filter true
        !(m.size >= SLOW_MIN_BYTES && (sp.uncached || sp.mbps in 0 until SLOW_MBPS))
    }

    /** Survivors of a search the console kept, as ordinary results (offsets within the original pieces). */
    fun fromSurvivors(pid: Int, type: ValueType, pieces: List<ScanSegment>, survivors: List<DbgSurvivor>, scans: Int): ScanResults {
        val segs = pieces.map { ScanSegment(it.base, it.length) }
        val sorted = survivors.sortedBy { it.address }
        var k = 0
        for (sv in sorted) {
            while (k < segs.size && sv.address >= segs[k].base + segs[k].length) k++
            if (k >= segs.size) break
            val seg = segs[k]
            if (sv.address < seg.base) continue
            seg.add((sv.address - seg.base).toInt(), sv.value, 0, type.size)
        }
        return ScanResults(pid, type, segs.filter { it.count > 0 }, scans)
    }

    /** [maps] cut into pieces of at most 1 GB, in address order. */
    fun segments(maps: List<DbgMap>): List<ScanSegment> {
        val out = ArrayList<ScanSegment>()
        for (m in maps.sortedBy { it.start }) {
            var a = m.start
            while (a < m.end) {
                val len = minOf(MAX_SEGMENT, m.end - a)
                out.add(ScanSegment(a, len.toInt()))
                a += len
            }
        }
        return out
    }

    /** The bytes a compare type sends: the value, or low and high for "between". Null if a needed value is missing or wrong. */
    fun scanData(type: ValueType, cmp: ScanCompare, value: String, value2: String): ByteArray? = when (cmp.values) {
        0 -> ByteArray(0)
        1 -> type.parse(value)
        else -> {
            val a = type.parse(value)
            val b = type.parse(value2)
            if (a == null || b == null) null else a + b
        }
    }

    /**
     * The first scan of a search, over [maps] of process [pid]. [progress] gets bytes done and bytes in all;
     * [cancelled] is checked between pieces. Needs a connection whose scans are unlocked.
     */
    fun first(
        dbg: Ps5Debug, pid: Int, maps: List<DbgMap>, type: ValueType, cmp: ScanCompare, data: ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }
    ): ScanResults {
        require(cmp.first && cmp != ScanCompare.UNKNOWN) { "${cmp.label} can't start a search here" }
        val segs = segments(maps)
        val all = segs.sumOf { it.length.toLong() }
        var done = 0L
        var found = 0L
        progress(0, all)
        for (seg in segs) {
            if (cancelled()) break
            dbg.scanStart(pid, seg.base, seg.length, type, cmp.wire, data) { off, buf, at ->
                if (++found > MAX_RESULTS) throw TooManyResults(MAX_RESULTS)
                seg.add(off, buf, at, type.size)
            }
            done += seg.length
            progress(done, all)
        }
        return ScanResults(pid, type, segs.filter { it.count > 0 }, 1)
    }

    /** A next scan: keeps the matches of [prev] that fit [cmp] now, with their new values. */
    fun next(
        dbg: Ps5Debug, prev: ScanResults, cmp: ScanCompare, data: ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }
    ): ScanResults {
        val out = ArrayList<ScanSegment>()
        var done = 0L
        progress(0, prev.total)
        for (seg in prev.segments) {
            if (cancelled()) break
            val kept = ScanSegment(seg.base, seg.length)
            dbg.scanCount(prev.pid, seg.base, prev.type, cmp.wire, data, seg.offsets, seg.values, seg.count) { off, buf, at ->
                kept.add(off, buf, at, prev.type.size)
            }
            if (kept.count > 0) out.add(kept)
            done += seg.count
            progress(done, prev.total)
        }
        return ScanResults(prev.pid, prev.type, out, prev.scans + 1)
    }

    /** Current values of [hits], read in one request (needs an unlocked connection). */
    fun refresh(dbg: Ps5Debug, pid: Int, type: ValueType, hits: List<MemoryHit>): List<MemoryHit> {
        if (hits.isEmpty()) return hits
        val addrs = LongArray(hits.size) { hits[it].address }
        val raw = dbg.readMany(pid, addrs, hits.size, type.size)
        return hits.mapIndexed { i, h -> MemoryHit(h.address, raw.copyOfRange(i * type.size, (i + 1) * type.size), h.value) }
    }
}

/**
 * A byte pattern around a saved address, to find it again after the game restarts and its memory moves ("Find
 * again"). The value itself, and anything that looks like an address (it changes from run to run), is left out.
 */
class Signature(val pattern: ByteArray, val mask: ByteArray, val valueAt: Int) {
    fun patternHex(): String = pattern.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    fun maskText(): String = mask.joinToString("") { if (it.toInt() == 1) "1" else "0" }

    companion object {
        const val BEFORE = 24
        const val AFTER = 24

        /** At least this many bytes must be kept, or the pattern would match almost anywhere. */
        private const val MIN_KEPT = 8

        /**
         * The pattern for a [size]-byte value at [address], from [window] (the bytes from address - BEFORE to
         * address + size + AFTER). [maps] tell which 8-byte numbers are addresses. Null when too little is left.
         */
        fun make(address: Long, size: Int, window: ByteArray, maps: List<DbgMap>): Signature? {
            val start = address - BEFORE
            if (window.size < BEFORE + size + AFTER) return null
            val mask = ByteArray(window.size) { 1 }
            for (i in BEFORE until BEFORE + size) mask[i] = 0
            // Every 8-byte aligned number that points into mapped memory is a pointer: it moves with the memory
            var a = (start + 7) and 7L.inv()
            while (a + 8 <= start + window.size) {
                val i = (a - start).toInt()
                var v = 0L
                for (k in 7 downTo 0) v = (v shl 8) or (window[i + k].toLong() and 0xFF)
                if (v != 0L && maps.any { v >= it.start && v < it.end }) for (k in 0 until 8) mask[i + k] = 0
                a += 8
            }
            // Zero bytes say little; if almost everything is zero the pattern would match everywhere
            val kept = (window.indices).count { mask[it].toInt() == 1 && window[it] != 0.toByte() }
            if (kept < MIN_KEPT) return null
            return Signature(window, mask, BEFORE)
        }

        fun parse(patternHex: String, maskText: String, valueAt: Int): Signature? {
            val p = CheatFiles.hex(patternHex) ?: return null
            if (maskText.length != p.size || p.isEmpty()) return null
            return Signature(p, ByteArray(p.size) { if (maskText[it] == '1') 1 else 0 }, valueAt)
        }
    }
}
