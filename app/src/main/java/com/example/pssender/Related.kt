package com.example.pssender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// Related values: games keep the numbers of one thing together (health, max health, armor, stamina). Around a value that
// was found, this reads a wider stretch of memory, leaves out what can't be a game number (zeros, garbage, addresses),
// and puts first what is most likely related: the same number (often the maximum), then numbers of the same size.
// Each is one tap from a cheat.
// ---------------------------------------------------------------------------------------------

/** How far around the value is looked at, each way. */
const val RELATED_SPAN = 0x100

/** At most this many are listed. */
const val RELATED_MAX = 12

/** Why a value nearby looks related. */
enum class RelatedWhy(val res: Int) {
    SAME(R.string.related_same),
    SIMILAR(R.string.related_similar),
    NUMBER(R.string.related_number)
}

/** One value near the found one: [offset] bytes from it, its text, and why it is listed. */
data class RelatedValue(val offset: Int, val text: String, val why: RelatedWhy)

/** Whether [v] looks like a number a game shows (not 0, not huge, not a fraction of nothing). */
fun plausibleGameNumber(t: ValueType, v: Double): Boolean = when {
    v.isNaN() || v.isInfinite() -> false
    v == 0.0 -> false
    t.isFloat -> kotlin.math.abs(v) in 0.01..1_000_000.0
    else -> v in 1.0..10_000_000.0
}

/**
 * The values in [bytes] (read from [span] bytes before the found value to [span] after it) that look related to [found]:
 * the same number first, then numbers within ten times of it, then other plausible numbers; nearest first in each.
 */
fun relatedValues(t: ValueType, bytes: ByteArray, span: Int, found: Double?): List<RelatedValue> {
    val out = ArrayList<RelatedValue>()
    var off = -span
    while (off <= span) {
        val at = off + span
        if (off != 0 && at >= 0 && at + t.size <= bytes.size) {
            val piece = bytes.copyOfRange(at, at + t.size)
            val v = numberOf(t, piece)
            if (v != null && plausibleGameNumber(t, v)) {
                val why = when {
                    found != null && v == found -> RelatedWhy.SAME
                    found != null && found != 0.0 && v / found in 0.1..10.0 -> RelatedWhy.SIMILAR
                    else -> RelatedWhy.NUMBER
                }
                out.add(RelatedValue(off, t.format(piece), why))
            }
        }
        off += t.size
    }
    return out.sortedWith(compareBy<RelatedValue> { it.why.ordinal }.thenBy { kotlin.math.abs(it.offset) }).take(RELATED_MAX)
}

/** The related values around [address]; [onMake] makes a cheat of one (its address, the type, its value now). */
@Composable
internal fun RelatedValues(address: Long, type: ValueType, onMake: (Long, ValueType, String) -> Unit) {
    var bytes by remember(address, type) { mutableStateOf<ByteArray?>(null) }
    var read by remember(address, type) { mutableStateOf(false) }
    LaunchedEffect(address, type) {
        bytes = MemorySession.peek(address - RELATED_SPAN, RELATED_SPAN * 2 + type.size)
        read = true
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val b = bytes
        if (b == null) {
            Text(if (read) str(R.string.related_unreadable) else "…", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val found = numberOf(type, b.copyOfRange(RELATED_SPAN, RELATED_SPAN + type.size))
        val list = remember(b) { relatedValues(type, b, RELATED_SPAN, found) }
        Text(str(R.string.related_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list.isEmpty()) Text(str(R.string.related_none), style = MaterialTheme.typography.bodySmall)
        list.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        (if (r.offset < 0) "-" else "+") + "0x%X".format(kotlin.math.abs(r.offset)) + "  " + r.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(str(r.why.res), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onMake(address + r.offset, type, r.text) }) { Text(str(R.string.helper_make_cheat)) }
            }
        }
    }
}
