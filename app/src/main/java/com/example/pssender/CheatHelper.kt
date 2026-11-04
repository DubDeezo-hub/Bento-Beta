package com.example.pssender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// The cheat-finding helper: instead of picking scan types, the person says what they see in the game ("my coins are 250",
// "my health bar went down") and the helper runs the matching scans, step by step, until a few addresses are left. Then
// each can be made into a cheat. It drives the same search as the Scan page (MemorySession), so the person can carry on
// there at any point.
// ---------------------------------------------------------------------------------------------

/**
 * What the person is looking for: a number shown in the game, something without a number (a bar, a meter), something
 * that switches on and off (a door, an unlock: two snapshots compared), or a timer or clock.
 */
enum class HelperKind { NUMBER, BAR, SWITCH, TIMER }

/** What happened to a bar since the last look, and the scan that matches it. */
enum class HelperChange(val compare: ScanCompare) {
    UP(ScanCompare.INCREASED),
    DOWN(ScanCompare.DECREASED),
    SAME(ScanCompare.UNCHANGED),
    CHANGED(ScanCompare.CHANGED)
}

/** Few enough left to look at one by one. */
const val HELPER_FEW = 12

/** The value type a helper search starts with: whole numbers for shown numbers, decimals for bars (games keep those as floats). */
fun helperStartType(kind: HelperKind, decimals: Boolean): ValueType = when {
    kind == HelperKind.SWITCH -> ValueType.BYTE // flags are single bytes, 0 or 1
    kind == HelperKind.BAR || kind == HelperKind.TIMER -> ValueType.FLOAT
    decimals -> ValueType.FLOAT
    else -> ValueType.INT
}

/** What the helper should say next, from how many addresses are left (-1: no search yet). */
enum class HelperStage { START, NARROW, FEW, NONE }

fun helperStage(left: Long): HelperStage = when {
    left < 0 -> HelperStage.START
    left == 0L -> HelperStage.NONE
    left <= HELPER_FEW -> HelperStage.FEW
    else -> HelperStage.NARROW
}

@Composable
internal fun CheatHelperDialog(onClose: () -> Unit, onMakeCheat: (MemoryHit, ValueType) -> Unit, onOpenHit: (MemoryHit, ValueType) -> Unit) {
    val m = MemorySession
    var kind by remember { mutableStateOf<HelperKind?>(null) }
    var decimals by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    val left = when {
        m.keptTotal >= 0 -> m.keptTotal
        m.results != null -> m.results!!.total
        else -> -1L
    }
    val stage = helperStage(left)

    fun begin(k: HelperKind) {
        m.newSearch()
        m.type = helperStartType(k, decimals)
        if (k == HelperKind.NUMBER) {
            m.compare = ScanCompare.EXACT
            m.value1 = number.trim()
        } else {
            m.compare = ScanCompare.UNKNOWN
        }
        m.firstScan()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.helper_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val k = kind
                if (k == null) {
                    // First: what kind of thing
                    Text(str(R.string.helper_pick))
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { kind = HelperKind.NUMBER; m.newSearch() }) {
                        Text(str(R.string.helper_kind_number))
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { kind = HelperKind.BAR; m.newSearch() }) {
                        Text(str(R.string.helper_kind_bar))
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { kind = HelperKind.SWITCH; m.newSearch() }) {
                        Text(str(R.string.helper_kind_switch))
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { kind = HelperKind.TIMER; m.newSearch() }) {
                        Text(str(R.string.helper_kind_timer))
                    }
                    return@Column
                }
                if (m.scanning) {
                    Text(str(R.string.helper_searching))
                    LinearProgressIndicator(
                        progress = { if (m.progressAll > 0) m.progressDone.toFloat() / m.progressAll.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    return@Column
                }
                if (m.status.isNotBlank()) {
                    Text(m.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (stage) {
                    HelperStage.START -> if (k == HelperKind.NUMBER) {
                        Text(str(R.string.helper_number_first))
                        NumberField(number) { number = it }
                        Button(enabled = number.isNotBlank(), onClick = { begin(k) }) { Text(str(R.string.helper_start)) }
                    } else {
                        Text(str(when (k) {
                            HelperKind.SWITCH -> R.string.helper_switch_first
                            HelperKind.TIMER -> R.string.helper_timer_first
                            else -> R.string.helper_bar_first
                        }))
                        Button(onClick = { begin(k) }) { Text(str(R.string.helper_start)) }
                    }
                    HelperStage.NARROW -> {
                        Text(str(R.string.helper_left, left))
                        if (k == HelperKind.NUMBER) {
                            Text(str(R.string.helper_number_next))
                            NumberField(number) { number = it }
                            Button(enabled = number.isNotBlank(), onClick = {
                                m.compare = ScanCompare.EXACT
                                m.value1 = number.trim()
                                m.nextScan()
                            }) { Text(str(R.string.helper_next)) }
                        } else if (k == HelperKind.SWITCH) {
                            // Two snapshots compared: flip it in the game (open, close, unlock), or leave it alone
                            Text(str(R.string.helper_switch_next))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ChangeButton(R.string.helper_flipped, HelperChange.CHANGED)
                                ChangeButton(R.string.helper_same, HelperChange.SAME)
                            }
                        } else {
                            Text(str(if (k == HelperKind.TIMER) R.string.helper_timer_next else R.string.helper_bar_next))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ChangeButton(R.string.helper_up, HelperChange.UP)
                                ChangeButton(R.string.helper_down, HelperChange.DOWN)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ChangeButton(R.string.helper_same, HelperChange.SAME)
                                ChangeButton(R.string.helper_changed, HelperChange.CHANGED)
                            }
                        }
                    }
                    HelperStage.FEW -> {
                        Text(str(R.string.helper_few, left))
                        val t = m.searchType
                        m.hits.take(HELPER_FEW).forEach { hit ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    hexAddr(hit.address) + " = " + t.format(hit.value),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                // Watch it while playing (with its graph), or look at the values kept next to it
                                TextButton(onClick = { m.watch(hit.address, t) }) { Text(str(R.string.memory_watch)) }
                                TextButton(onClick = { onOpenHit(hit, t) }) { Text(str(R.string.related_short)) }
                                TextButton(onClick = { onMakeCheat(hit, t) }) { Text(str(R.string.helper_make_cheat)) }
                            }
                        }
                        Text(
                            str(when (k) {
                                HelperKind.TIMER -> R.string.helper_timer_few
                                HelperKind.SWITCH -> R.string.helper_switch_few
                                else -> R.string.helper_few_hint
                            }),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    HelperStage.NONE -> {
                        Text(str(if (k == HelperKind.NUMBER && !decimals) R.string.helper_none_number else R.string.helper_none))
                        if (k == HelperKind.NUMBER && !decimals) {
                            Button(onClick = { decimals = true; m.newSearch() }) { Text(str(R.string.helper_try_decimals)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } },
        dismissButton = {
            if (kind != null && !m.scanning) TextButton(onClick = {
                m.newSearch()
                kind = null
                decimals = false
                number = ""
            }) { Text(str(R.string.helper_restart)) }
        }
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(20)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        label = { Text(str(R.string.helper_number_label)) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ChangeButton(label: Int, change: HelperChange) {
    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
        MemorySession.compare = change.compare
        MemorySession.nextScan()
    }) { Text(str(label)) }
}
