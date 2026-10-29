package com.example.pssender // change to match your project's package name

// ---------------------------------------------------------------------------------------------
// Pointers: a value that moves every time the game starts (health on the heap, say) is usually reached from a fixed
// place inside the game's own file (its eboot) through a few pointers: [[eboot + 0x1A2B0] + 0x18] + 0x40. Such a path
// still leads to the value after a restart, when the address itself does not. The search here works backwards from
// the value's address: which places hold a pointer just below it, which places point just below those, and so on,
// until a place inside the eboot turns up. Plain Kotlin; the debugger is only reached through [PointerScan.Memory].
// ---------------------------------------------------------------------------------------------

/** A way to the value: start at the eboot's load address + [baseOffset], then for each of [offsets]: read a pointer there, add the offset. */
data class PointerPath(val baseOffset: Long, val offsets: List<Long>) {
    val depth: Int get() = offsets.size

    /** "[eboot+1A2B0]+18 → +40": what the person sees. */
    fun text(): String {
        val sb = StringBuilder("[eboot+").append(java.lang.Long.toHexString(baseOffset).uppercase()).append(']')
        offsets.forEachIndexed { i, o ->
            if (i > 0) sb.append(" →")
            sb.append(" +").append(java.lang.Long.toHexString(o).uppercase())
        }
        return sb.toString()
    }

    /** How it is saved: "1a2b0:18,40". */
    fun encode(): String = java.lang.Long.toHexString(baseOffset) + ":" + offsets.joinToString(",") { java.lang.Long.toHexString(it) }

    companion object {
        fun decode(s: String): PointerPath? {
            val t = s.trim()
            if (t.isEmpty() || ':' !in t) return null
            return try {
                val base = java.lang.Long.parseUnsignedLong(t.substringBefore(':'), 16)
                val offs = t.substringAfter(':').split(',').filter { it.isNotBlank() }.map { java.lang.Long.parseUnsignedLong(it.trim(), 16) }
                if (offs.isEmpty() || offs.size > 8) null else PointerPath(base, offs)
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/** A place in memory that holds a pointer: where it is ([at]) and what it points to ([value]). */
data class PointerHit(val at: Long, val value: Long)

object PointerScan {
    /** How far below a place a pointer may point and still count (the offset inside the thing it points to). */
    const val DEFAULT_MAX_OFFSET = 0x1000L
    /** Search results bigger than this are not followed further: they would take too long to look through. */
    const val MAX_LEVEL = 50_000
    const val MAX_PATHS = 5_000

    /** What the search needs from the debugger. */
    interface Memory {
        /** Places in the [regions] holding an 8-byte value from [low] to [high]. */
        fun pointersBetween(low: Long, high: Long, staticOnly: Boolean): List<PointerHit>

        /** The 8-byte values at [addresses] (0 where nothing can be read). */
        fun read64(addresses: LongArray): LongArray

        fun progress(step: Int, steps: Int) {}
        fun cancelled(): Boolean = false
    }

    /** The eboot's place in memory: where it is loaded and where it ends. */
    class Image(val base: Long, val end: Long) {
        operator fun contains(a: Long) = a in base until end
    }

    /**
     * The eboot from a process's [maps]: the regions named after it, and an unnamed region right after one of them (its
     * zeroed data, which has no name). Null when there is no eboot region.
     */
    fun imageOf(maps: List<DbgMap>, process: String = "eboot"): Image? {
        val sorted = maps.sortedBy { it.start }
        val named = sorted.filter { it.name.contains(process.substringBefore('.'), ignoreCase = true) }
        if (named.isEmpty()) return null
        var end = named.maxOf { it.end }
        // the data that follows the file in memory (.bss) has no name: take regions that start where the last one ends
        for (m in sorted) {
            if (m.start == end && m.name.isBlank()) end = m.end
        }
        return Image(named.minOf { it.start }, end)
    }

    /** Groups sorted addresses that lie close together, so each group can be searched with one window. */
    fun clusters(sorted: List<Long>, maxOffset: Long, gap: Long = 64L shl 20): List<LongRange> {
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<LongRange>()
        var lo = sorted[0]
        var hi = sorted[0]
        for (a in sorted.drop(1)) {
            if (a - hi > gap) {
                out.add((lo - maxOffset)..hi)
                lo = a
            }
            hi = a
        }
        out.add((lo - maxOffset)..hi)
        return out
    }

    /**
     * Of [hits], the ones that point at most [maxOffset] below one of [targets] (sorted), each with the offset to every
     * target it reaches.
     */
    fun links(hits: List<PointerHit>, targets: LongArray, maxOffset: Long): List<Pair<PointerHit, List<Long>>> {
        val out = ArrayList<Pair<PointerHit, List<Long>>>()
        for (h in hits) {
            if (h.at and 7L != 0L) continue // pointers sit on 8-byte boundaries
            // targets from value to value + maxOffset
            var i = lowerBound(targets, h.value)
            val reached = ArrayList<Long>()
            while (i < targets.size && targets[i] - h.value <= maxOffset) {
                reached.add(targets[i])
                i++
            }
            if (reached.isNotEmpty()) out.add(h to reached)
        }
        return out
    }

    private fun lowerBound(a: LongArray, v: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Finds paths from the eboot [image] to [target], at most [depth] pointers long. Shorter paths and smaller offsets
     * come first.
     */
    fun find(mem: Memory, target: Long, image: Image, depth: Int = 3, maxOffset: Long = DEFAULT_MAX_OFFSET): List<PointerPath> {
        val paths = ArrayList<PointerPath>()
        // For each place found so far: the offsets from it to the target (the rest of the path)
        var level: Map<Long, List<Long>> = mapOf(target to emptyList())
        for (d in 1..depth) {
            if (mem.cancelled() || level.isEmpty() || paths.size >= MAX_PATHS) break
            val targets = level.keys.sorted().toLongArray()
            val last = d == depth
            // On the last step only places inside the eboot are of use, and searching only there is quick
            val windows = clusters(targets.toList(), maxOffset)
            val hits = ArrayList<PointerHit>()
            for ((wi, w) in windows.withIndex()) {
                if (mem.cancelled()) break
                mem.progress((d - 1) * 10 + wi * 10 / windows.size, depth * 10)
                hits.addAll(mem.pointersBetween(w.first.coerceAtLeast(0L), w.last, staticOnly = last))
                if (hits.size > MAX_LEVEL * 4) break
            }
            val next = HashMap<Long, List<Long>>()
            for ((hit, reached) in links(hits, targets, maxOffset)) {
                for (t in reached) {
                    val rest = level[t] ?: continue
                    val tail = listOf(t - hit.value) + rest
                    if (hit.at in image) {
                        if (paths.size < MAX_PATHS) paths.add(PointerPath(hit.at - image.base, tail))
                    } else if (!last) {
                        // keep the shortest way on from each place
                        val had = next[hit.at]
                        if (had == null || tail.sum() < had.sum()) next[hit.at] = tail
                    }
                }
            }
            level = if (next.size > MAX_LEVEL) next.entries.sortedBy { it.value.sum() }.take(MAX_LEVEL).associate { it.key to it.value } else next
        }
        return paths.distinct().sortedWith(compareBy({ it.depth }, { it.offsets.sum() }, { it.baseOffset }))
    }

    /**
     * Where each of [paths] leads now (0 where a pointer on the way is null or can't be read). Reads in steps, many
     * paths at a time.
     */
    fun resolve(mem: Memory, base: Long, paths: List<PointerPath>): LongArray {
        val at = LongArray(paths.size) { base + paths[it].baseOffset }
        val maxDepth = paths.maxOfOrNull { it.depth } ?: 0
        for (step in 0 until maxDepth) {
            val idx = paths.indices.filter { step < paths[it].depth && at[it] != 0L }
            if (idx.isEmpty()) break
            val values = mem.read64(LongArray(idx.size) { at[idx[it]] })
            for ((k, i) in idx.withIndex()) {
                val p = values[k]
                at[i] = if (p == 0L || p < 0x10000L) 0L else p + paths[i].offsets[step]
            }
        }
        return at
    }
}
