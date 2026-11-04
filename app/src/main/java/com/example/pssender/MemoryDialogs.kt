package com.example.pssender

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// The Memory tab's dialogs: adding and editing a cheat, a line of the hex view, a watched address, importing cheats,
// text search and its hits, the undo history and the cheat source. The tab itself is in Memory.kt.
// ---------------------------------------------------------------------------------------------

/** Adds a cheat ([new] set) or edits one ([cheat] set). */
@Composable
internal fun CheatDialog(cheat: Cheat?, new: MemoryDialog.NewCheat?, onClose: () -> Unit) {
    val m = MemorySession
    var name by remember { mutableStateOf(cheat?.name ?: "") }
    var group by remember { mutableStateOf(cheat?.group ?: m.cheats.lastOrNull()?.group ?: "") }
    var address by remember {
        mutableStateOf((cheat?.address ?: new?.address)?.let { java.lang.Long.toHexString(it).uppercase() } ?: "")
    }
    var type by remember { mutableStateOf(cheat?.type ?: new?.type ?: ValueType.INT) }
    var value by remember { mutableStateOf(cheat?.value ?: new?.value ?: "") }
    var note by remember { mutableStateOf(cheat?.note ?: "") }
    var rule by remember { mutableStateOf(cheat?.rule ?: FreezeRule.EXACT) }
    val addr = parseAddress(address)
    val valueOk = value.isBlank() || type.parse(value) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(if (cheat == null) R.string.memory_cheat_add else R.string.memory_cheat_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Field(str(R.string.memory_cheat_name), name) { name = it }
                Field(str(R.string.memory_cheat_group), group) { group = it }
                Field(str(R.string.memory_cheat_address), address) { address = it }
                if (addr == null && address.isNotBlank()) {
                    Text(str(R.string.memory_bad_address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                ChipRow {
                    ValueType.entries.forEach { t -> Chip(str(t.res), type == t) { type = t } }
                }
                Field(str(R.string.memory_value), value) { value = it }
                ValuePresets(type) { value = it }
                // When frozen: hold it exactly, or only keep it from going below / above the value
                Text(str(R.string.freeze_rule_title), style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    FreezeRule.entries.forEach { r -> Chip(str(r.res), rule == r) { rule = r } }
                }
                if (rule != FreezeRule.EXACT) {
                    Text(str(R.string.freeze_rule_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The person's own words about it: what it does, which game version it works on
                Field(str(R.string.memory_cheat_note), note) { note = it.take(CHEAT_NOTE_MAX) }
                if (!valueOk) {
                    Text(str(R.string.memory_bad_value, str(type.res)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (cheat != null) {
                    Text(
                        str(if (cheat.signature != null) R.string.memory_cheat_findable else R.string.memory_cheat_not_findable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(enabled = !m.busy && addr != null && value.isNotBlank() && valueOk, onClick = {
                        if (addr != null) m.writeValue(addr, type, value)
                    }) { Text(str(R.string.memory_write_now)) }
                    if (cheat != null) {
                        if (cheat.signature != null) {
                            TextButton(enabled = !m.busy, onClick = {
                                m.findAgain(cheat)
                                onClose()
                            }) { Text(str(R.string.memory_find_again)) }
                        } else {
                            TextButton(enabled = !m.busy, onClick = {
                                m.captureSignature(cheat)
                                onClose()
                            }) { Text(str(R.string.memory_make_findable)) }
                        }
                        TextButton(enabled = !m.busy, onClick = {
                            m.findPointers(cheat)
                            onClose()
                        }) { Text(str(R.string.ptr_find)) }
                        TextButton(onClick = {
                            m.watch(cheat.address, cheat.type)
                            onClose()
                        }) { Text(str(R.string.memory_watch)) }
                        TextButton(onClick = {
                            m.removeCheat(cheat)
                            onClose()
                        }) { Text(str(R.string.action_remove)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = addr != null && valueOk, onClick = {
                if (addr != null) {
                    if (cheat == null) {
                        val added = m.addCheat(name, addr, type, value, group, note = note, rule = rule)
                        // From the helper: look for a pointer path right away, so the cheat still works after a restart
                        if (new?.autoPointer == true) m.findPointersAuto(added)
                    } else {
                        val moved = addr != cheat.address || type != cheat.type
                        val c = cheat.copy(name = name.trim().ifBlank { cheat.name }, address = addr, type = type, value = value.trim(), group = group.trim(), note = note.trim(), rule = rule)
                        // A cheat moved by hand no longer matches its old signature
                        m.updateCheat(if (moved) c.copy(sigHex = "", sigMask = "", sigAt = 0) else c)
                    }
                }
                onClose()
            }) { Text(str(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

/** Edits the bytes of one line of the hex view. */
@Composable
internal fun HexLineDialog(d: MemoryDialog.HexLine, onClose: () -> Unit) {
    val m = MemorySession
    var text by remember { mutableStateOf(bytesText(d.bytes)) }
    val parsed = CheatFiles.hex(text)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hexAddr(d.address), fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_hex_edit_help), style = MaterialTheme.typography.bodySmall)
                Field(str(R.string.memory_hex_bytes), text) { text = it }
                if (parsed == null || parsed.isEmpty()) {
                    Text(str(R.string.memory_hex_bad), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                AllTypes(d.bytes.copyOf(8))
            }
        },
        confirmButton = {
            TextButton(enabled = !m.busy && parsed != null && parsed.isNotEmpty(), onClick = {
                if (parsed != null) m.writeBytes(d.address, parsed, inOpen = true)
                onClose()
            }) { Text(str(R.string.memory_write)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

@Composable
internal fun WatchDialog(w: Watch, onClose: () -> Unit, onOpen: (MemoryDialog) -> Unit) {
    val m = MemorySession
    val now = w.value?.let { w.type.format(it) } ?: ""
    var value by remember { mutableStateOf(now) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hexAddr(w.address), fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_hit_now, str(w.type.res), now.ifBlank { "…" }), style = MaterialTheme.typography.bodyMedium)
                Field(str(R.string.memory_new_value), value) { value = it }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { onOpen(MemoryDialog.NewCheat(w.address, w.type, value)) }) { Text(str(R.string.memory_cheat_add_short)) }
                    TextButton(onClick = {
                        m.unwatch(w)
                        onClose()
                    }) { Text(str(R.string.memory_unwatch)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !m.busy && w.type.parse(value) != null, onClick = {
                m.writeValue(w.address, w.type, value)
                onClose()
            }) { Text(str(R.string.memory_write)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

@Composable
internal fun ImportDialog(ctx: Context, onClose: () -> Unit) {
    val m = MemorySession
    var text by remember { mutableStateOf("") }
    // A cheat file someone shared (saved with "Save as file"): its text goes into the box, to check before importing
    val openFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) text = readCheatShare(ctx, uri) ?: AppText.get(R.string.cheatshare_unreadable)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.memory_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_import_help), style = MaterialTheme.typography.bodySmall)
                LinesField(str(R.string.memory_import_box), text, 4) { text = it }
                Row {
                    TextButton(onClick = { text = clipboardText(ctx) }) { Text(str(R.string.install_paste)) }
                    TextButton(onClick = { openFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) {
                        Text(str(R.string.cheatshare_open))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                m.importText(text)
                onClose()
            }) { Text(str(R.string.memory_import)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

/** The word to look for, how it may be stored, and the search button. */
@Composable
internal fun TextSearchPanel(s: MemorySnapshot) {
    val m = MemorySession
    TipTitle(str(R.string.memory_text_title), Tip.MEM_TEXT, style = MaterialTheme.typography.labelLarge)
    if (!s.info.isNg) {
        Text(str(R.string.memory_text_needs_ng), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Field(str(R.string.memory_text_field), m.textQuery) { m.textQuery = it }
    ChipRow {
        TextEncoding.entries.forEach { e ->
            val on = e in m.textEncodings
            Chip(e.label, on, enabled = !m.busy) {
                // At least one stays chosen
                m.textEncodings = if (on) (m.textEncodings - e).ifEmpty { setOf(e) } else m.textEncodings + e
            }
        }
    }
    Text(str(R.string.memory_text_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!m.scanning) {
        Button(enabled = !m.busy && m.textQuery.isNotEmpty(), onClick = { m.textSearch() }) { Text(str(R.string.memory_text_search)) }
    }
    val total = m.textTotal
    if (total >= 0) {
        Text(
            if (total > m.textHits.size) str(R.string.memory_text_more, total, m.textHits.size) else str(R.string.memory_text_found, total),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** One place the text was found: see it in memory, or write new text over it. */
@Composable
internal fun TextHitDialog(hit: TextHit, onClose: () -> Unit) {
    val m = MemorySession
    val ctx = LocalContext.current
    val now = m.textHits.firstOrNull { it.address == hit.address && it.encoding == hit.encoding } ?: hit
    var text by remember { mutableStateOf(now.written ?: m.textQuery) }
    val room = hit.length / hit.encoding.unit
    val fits = TextSearch.replacement(text, hit.encoding, hit.length) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hexAddr(hit.address), fontFamily = FontFamily.Monospace) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hit.encoding.label, style = MaterialTheme.typography.labelLarge)
                Text(now.before + "[" + (now.written ?: m.textQuery) + "]" + now.after, style = MaterialTheme.typography.bodyMedium)
                Field(str(R.string.memory_text_new), text) { text = it }
                Text(
                    if (fits) str(R.string.memory_text_room, room) else str(R.string.memory_text_too_long, room),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        m.browseTo(hit.address)
                        onClose()
                    }) { Text(str(R.string.memory_text_show)) }
                    TextButton(onClick = { copyText(ctx, "address", hexAddr(hit.address), AppText.get(R.string.memory_address_copied)) }) { Text(str(R.string.memory_copy_address)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = fits && text.isNotEmpty() && !m.busy, onClick = { m.writeText(hit, text, onClose) }) {
                Text(str(R.string.memory_text_write))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

/** Every change this session can take back, newest first. */
@Composable
internal fun UndoDialog(onClose: () -> Unit) {
    val m = MemorySession
    val steps = m.undoSteps
    val clock = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { TipTitle(str(R.string.undo_title), Tip.MEM_UNDO) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(str(R.string.undo_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                steps.asReversed().forEachIndexed { i, step ->
                    ListRow(
                        title = step.label,
                        detail = clock.format(java.util.Date(step.time)) + "  ·  " + step.parts.take(3).joinToString(", ") { hexAddr(it.address) } + (if (step.parts.size > 3) " …" else ""),
                        highlight = i == 0,
                        enabled = i == 0 && !m.busy,
                        mono = false
                    ) { m.undoLast() }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = steps.isNotEmpty() && !m.busy, onClick = {
                m.undoAll()
                onClose()
            }) { Text(str(R.string.undo_all)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
    if (steps.isEmpty()) LaunchedEffect(Unit) { onClose() }
}

@Composable
internal fun SourceDialog(onClose: () -> Unit) {
    val m = MemorySession
    var text by remember { mutableStateOf(m.cheatSource) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.memory_cheatfile_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.memory_cheatfile_source_help), style = MaterialTheme.typography.bodySmall)
                Field(str(R.string.memory_cheatfile_source_url), text) { text = it }
                TextButton(onClick = { text = CheatFiles.DEFAULT_SOURCE }) { Text(str(R.string.memory_cheatfile_source_reset)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                m.changeCheatSource(text)
                onClose()
            }) { Text(str(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

/** The text of a shared cheat file at [uri], or null when it can't be read (or is too big to be one). */
internal fun readCheatShare(ctx: Context, uri: android.net.Uri): String? = try {
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(CHEAT_SHARE_MAX + 1)
        if (bytes.size > CHEAT_SHARE_MAX) null else String(bytes, Charsets.UTF_8)
    }
} catch (_: Exception) {
    null
}

/** A shared cheat list is small; anything bigger than this is not one. */
const val CHEAT_SHARE_MAX = 1 shl 20

/** Reads at most [max] bytes (InputStream.readNBytes needs a newer Android). */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** The file name a shared cheat list is saved under: the game's title ID or name, then "-cheats.json". */
fun cheatShareName(titleId: String, game: String): String {
    val base = titleId.ifBlank { game }.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(40)
    return (base.ifEmpty { "my" }) + "-cheats.json"
}
