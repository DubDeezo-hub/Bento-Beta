package com.example.pssender

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

// ---------------------------------------------------------------------------------------------
// Storage: payloads kept inside the app, so they can be sent again without picking a file
// ---------------------------------------------------------------------------------------------

/** Largest payload the shelf accepts. Payloads are small; this stops a huge file being copied in by mistake. */
const val SHELF_MAX_BYTES = 64L * 1024 * 1024

data class ShelfItem(val name: String, val size: Long)

data class ShelfAdded(val name: String, val size: Long, val replaced: Boolean)

class ShelfTooLarge(val limit: Long) : IOException(AppText.get(R.string.shelf_too_large, fmtBytes(limit)))

fun shelfDir(ctx: Context): File = File(ctx.filesDir, "payloads").also { it.mkdirs() }

/** A file name that is safe to store: no folders, no odd characters, never empty. */
fun safeShelfName(raw: String): String {
    val last = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = last.map { c -> if (c.isISOControl() || c in "\\/:*?\"<>|") '_' else c }
        .joinToString("")
        .trim()
        .trimStart('.')
        .take(100)
        .trim()
    return cleaned.ifBlank { "payload.bin" }
}

/**
 * Copies [input] onto the shelf as [rawName]. A payload with the same name is replaced (handy for updating one),
 * but only once the new copy is complete. Throws [ShelfTooLarge] past [maxBytes].
 */
fun shelfAdd(dir: File, rawName: String, input: InputStream, maxBytes: Long = SHELF_MAX_BYTES): ShelfAdded {
    dir.mkdirs()
    val name = safeShelfName(rawName)
    val target = File(dir, name)
    val temp = File(dir, ".adding-$name")
    var size = 0L
    try {
        FileOutputStream(temp).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                size += n
                if (size > maxBytes) throw ShelfTooLarge(maxBytes)
                out.write(buf, 0, n)
            }
        }
        if (size == 0L) throw IOException(AppText.get(R.string.err_empty_file))
        val replaced = target.exists()
        if (replaced) target.delete()
        if (!temp.renameTo(target)) throw IOException(AppText.get(R.string.err_save, name))
        return ShelfAdded(name, size, replaced)
    } finally {
        temp.delete()
    }
}

/** Everything on the shelf, alphabetical. Unfinished copies (names starting with a dot) are left out. */
fun shelfList(dir: File): List<ShelfItem> =
    dir.listFiles().orEmpty()
        .filter { it.isFile && !it.name.startsWith(".") }
        .map { ShelfItem(it.name, it.length()) }
        .sortedBy { it.name.lowercase() }

/** Deletes one payload from the shelf. Only plain names inside the shelf folder are accepted. */
fun shelfRemove(dir: File, name: String): Boolean {
    val f = File(dir, name)
    return f.parentFile == dir && f.isFile && f.delete()
}

fun shelfAddedMessage(a: ShelfAdded): String =
    AppText.get(if (a.replaced) R.string.shelf_updated else R.string.shelf_added, a.name, fmtBytes(a.size))

fun shelfTooLargeMessage(limit: Long): String = AppText.get(R.string.shelf_too_large, fmtBytes(limit))

/** Copies a file picked with the system file picker onto the shelf. Returns a message for the status line. */
suspend fun shelfAddFromUri(ctx: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        try {
            val name = ctx.contentResolver.displayName(uri)
            val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext AppText.get(R.string.err_open_file)
            shelfAddedMessage(input.use { shelfAdd(shelfDir(ctx), name, it) })
        } catch (e: ShelfTooLarge) {
            shelfTooLargeMessage(e.limit)
        } catch (e: Exception) {
            failedText(e)
        }
    }

// ---------------------------------------------------------------------------------------------
// Payload tab
// ---------------------------------------------------------------------------------------------

/**
 * The shelf (saved payloads with one-tap Send), the payload chains, and the old "pick a file and send it once" flow.
 * [shelfVersion] changes whenever something else adds to the shelf, so the list reloads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayloadTab(
    host: String,
    port: String,
    onPortChange: (String) -> Unit,
    shelfVersion: Int,
    chains: List<Chain>,
    chainBusy: Boolean,
    onRunChain: (Chain) -> Unit,
    onSaveChain: (Chain) -> Unit,
    onDeleteChain: (Chain) -> Unit,
    onPinChain: (Chain) -> Unit
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var shelf by remember(shelfVersion) { mutableStateOf(shelfList(shelfDir(ctx))) }
    var oneOff by remember { mutableStateOf<Uri?>(null) }
    val idle = str(R.string.status_idle)
    var status by remember { mutableStateOf(idle) }
    var removing by remember { mutableStateOf<ShelfItem?>(null) }
    val canSend = host.isNotBlank() && port.trim().toIntOrNull() != null
    // A payload that would load etaHEN, kstuff or GoldHEN a second time waits for "Send anyway"
    var reloadAsk by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val guarded: (String, () -> Unit) -> Unit = { name, go ->
        scope.launch {
            val why = withContext(Dispatchers.IO) { ReloadGuard.warning(host, listOf(name), ActiveConsole.type) }
            if (why == null) go() else reloadAsk = why to go
        }
    }
    reloadAsk?.let { (why, go) ->
        AlertDialog(
            onDismissRequest = { reloadAsk = null },
            title = { Text(str(R.string.send_check_title)) },
            text = { Text(why) },
            confirmButton = { TextButton(onClick = { reloadAsk = null; go() }) { Text(str(R.string.reload_send_anyway)) } },
            dismissButton = { TextButton(onClick = { reloadAsk = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    val adder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            status = AppText.get(R.string.payload_adding)
            scope.launch {
                status = shelfAddFromUri(ctx, picked)
                shelf = shelfList(shelfDir(ctx))
            }
        }
    }
    val oneOffPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { oneOff = it }
    var showStore by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ShelfItem?>(null) }
    val type = ActiveConsole.type
    LaunchedEffect(type) { StoreUpdates.check(ctx, type) }
    if (showStore) {
        StoreDialog(type, onAdded = { shelf = shelfList(shelfDir(ctx)) }, onClose = {
            showStore = false
            StoreUpdates.seen()
        })
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(str(R.string.payload_remove_title, item.name)) },
            text = { Text(str(R.string.payload_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    shelfRemove(shelfDir(ctx), item.name)
                    ShelfChecks.forget(ctx, item.name)
                    PayloadFirmware.forget(ctx, item.name)
                    shelf = shelfList(shelfDir(ctx))
                    removing = null
                }) { Text(str(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    // A payload's checksum, and whether it still matches the one it was verified against
    details?.let { item ->
        var sha by remember(item.name) { mutableStateOf("") }
        var elf by remember(item.name) { mutableStateOf<ElfVerdict?>(null) }
        LaunchedEffect(item.name) {
            sha = withContext(Dispatchers.IO) { ShelfChecks.sha256Of(File(shelfDir(ctx), item.name)) }
            // Is it a sound payload for the console (64-bit x86-64 ELF, not cut short)? A .bin isn't checked
            elf = withContext(Dispatchers.IO) { try { elfCheck(item.name, File(shelfDir(ctx), item.name).readBytes()) } catch (_: Exception) { null } }
        }
        val checked = ShelfChecks.verified(ctx, item.name)
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(item.name) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(fmtBytes(item.size), style = MaterialTheme.typography.bodySmall)
                    elf?.let { v ->
                        Text(str(v.res), style = MaterialTheme.typography.bodySmall, color = if (v == ElfVerdict.OK) statusColors().good else statusColors().bad)
                    }
                    Text("SHA-256", style = MaterialTheme.typography.labelLarge)
                    Text(sha.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    when {
                        checked == null -> Text(str(R.string.shelf_not_verified), style = MaterialTheme.typography.bodySmall)
                        sha.isEmpty() -> {}
                        sha == checked.first -> Text(str(R.string.shelf_verified_by, checked.second), style = MaterialTheme.typography.bodySmall, color = statusColors().good)
                        else -> Text(str(R.string.shelf_changed), style = MaterialTheme.typography.bodySmall, color = statusColors().bad)
                    }
                    // From the store: its version, and the one before it when that was kept (one tap to go back)
                    val key = remember(item.name) { Store.keyOf(ctx, item.name) }
                    if (key != null) {
                        val tag = Store.installedTag(ctx, key)
                        if (tag.isNotEmpty()) Text(str(R.string.shelf_version, tag), style = MaterialTheme.typography.bodySmall)
                        if (Store.hasPrevious(ctx, key)) {
                            val prev = Store.previousTag(ctx, key)
                            OutlinedButton(onClick = {
                                val now = Store.switchVersion(ctx, key)
                                shelf = shelfList(shelfDir(ctx))
                                details = null
                                status = if (now != null) AppText.get(R.string.shelf_switched, now, prev) else AppText.get(R.string.shelf_switch_failed)
                            }) { Text(str(R.string.shelf_go_back, prev)) }
                            Text(str(R.string.shelf_go_back_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // The same payload to every saved console with an address (one after the other)
                    val others = remember { ProfileStore.load(ctx).filter { it.host.isNotBlank() && it.payloadPort.trim().toIntOrNull() != null } }
                    if (others.size > 1) {
                        OutlinedButton(enabled = canSend, onClick = {
                            details = null
                            status = AppText.get(R.string.send_all_sending, item.name, others.size)
                            scope.launch {
                                val results = withContext(Dispatchers.IO) {
                                    others.map { p ->
                                        val r = sendPayloadReply(File(shelfDir(ctx), item.name), p.host.trim(), p.payloadPort.trim().toInt())
                                        if (r.ok) notePayloadSent(p.host.trim(), item.name)
                                        p.name to r.ok
                                    }
                                }
                                status = sendAllSummary(item.name, results)
                            }
                        }) { Text(str(R.string.send_all, others.size)) }
                    }
                    // What others saw with this payload on the same firmware, and "How did it go?"
                    CompatSection(item.name, if (key != null) Store.installedTag(ctx, key) else "")
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text(str(R.string.action_close)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { removing = item; details = null }) { Text(str(R.string.action_remove)) }
                    TextButton(enabled = sha.isNotEmpty(), onClick = { copyText(ctx, "sha256", sha, AppText.get(R.string.help_copied)) }) { Text(str(R.string.shelf_copy_sha)) }
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TipTitle(str(R.string.payload_shelf_title), Tip.PAYLOAD_SHELF, style = MaterialTheme.typography.titleMedium)
        if (shelf.isEmpty()) {
            Text(str(R.string.payload_shelf_empty, ActiveConsole.type.hen), style = MaterialTheme.typography.bodySmall)
        }
        // The shelf as round tiles: tap a tile for its details (checksum, Remove), Send under it
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            shelf.forEach { item ->
                val checked = remember(item.name, item.size) { ShelfChecks.verified(ctx, item.name) }
                ShelfTile(
                    item = item,
                    verified = checked != null,
                    canSend = canSend,
                    onDetails = { details = item },
                    onSend = {
                        guarded(item.name) {
                            status = AppText.get(R.string.payload_sending, item.name)
                            scope.launch {
                                val sent = sendPayloadReply(File(shelfDir(ctx), item.name), host.trim(), port.trim().toInt())
                                if (sent.ok) notePayloadSent(host, item.name)
                                buzz(view, sent.ok)
                                status = AppText.get(R.string.payload_result, item.name, sent.text)
                            }
                        }
                    }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { adder.launch(arrayOf("*/*")) }) { Text(str(R.string.payload_add)) }
            OutlinedButton(onClick = { showStore = true }) { Text(str(R.string.store_open)) }
        }
        if (StoreUpdates.newer.isNotEmpty()) {
            Text(
                str(R.string.store_updates_out, StoreUpdates.newer.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (StoreUpdates.fresh.isNotEmpty()) {
            Text(
                str(R.string.store_new_out, StoreUpdates.fresh.take(6).joinToString(", ") + if (StoreUpdates.fresh.size > 6) " …" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        ChainsSection(
            chains = chains,
            shelf = shelf,
            canSend = canSend && !chainBusy,
            onRun = onRunChain,
            onSave = onSaveChain,
            onDelete = onDeleteChain,
            onPin = onPinChain
        )

        Text(str(R.string.payload_once_title), style = MaterialTheme.typography.titleMedium)
        Button(onClick = { oneOffPicker.launch(arrayOf("*/*")) }) { Text(str(R.string.payload_choose)) }
        Text(oneOff?.let { ctx.contentResolver.displayName(it) } ?: str(R.string.upload_none))
        Button(
            enabled = canSend && oneOff != null,
            onClick = {
                val picked = oneOff!!
                val name = ctx.contentResolver.displayName(picked)
                guarded(name) {
                    status = AppText.get(R.string.status_sending)
                    scope.launch {
                        val sent = sendPayloadUri(ctx.contentResolver, picked, host.trim(), port.trim().toInt())
                        // Like a send from the shelf: remembered for the double-load warning and the crash diary, and a buzz
                        if (sent.ok) notePayloadSent(host, name)
                        buzz(view, sent.ok)
                        status = sent.text
                    }
                }
            }
        ) { Text(str(R.string.payload_send)) }
        Text(status)
        HelpLink(status)
        // Set once and then left alone: folded away
        Foldable(str(R.string.settings_section), Tip.PAYLOAD_PORT, summary = str(R.string.settings_port_summary, port)) {
            Field(str(R.string.payload_loader_port), port, onPortChange)
        }
    }
}

/** One payload on the shelf: a round tile with its first letter (a small red tick when verified), its name and size, Send. */
@Composable
private fun ShelfTile(item: ShelfItem, verified: Boolean, canSend: Boolean, onDetails: () -> Unit, onSend: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val big = ReloadGuard.kindOf(item.name) != null
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF2A0A10), cs.surface)))
                    .border(2.dp, if (big) BLOOD_BRIGHT else cs.outlineVariant, CircleShape)
                    .clickable(onClick = onDetails),
                contentAlignment = Alignment.Center
            ) {
                Text(item.name.take(1).lowercase(), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (verified) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(BLOOD_BRIGHT),
                    contentAlignment = Alignment.Center
                ) { Text("✓", color = Color(0xFF1A0206), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp) }
            }
        }
        Text(item.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Text(fmtBytes(item.size), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .height(36.dp)
                .clip(CircleShape)
                .background(if (canSend) bloodGradient else Brush.linearGradient(listOf(cs.surfaceContainerHigh, cs.surfaceContainerHigh)))
                .clickable(enabled = canSend, onClick = onSend)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) { Text(str(R.string.action_send), color = if (canSend) Color.White else cs.onSurfaceVariant, fontWeight = FontWeight.Bold) }
    }
}

/** "Sent X to 3 consoles" or, when some failed, which ones didn't take it. */
fun sendAllSummary(name: String, results: List<Pair<String, Boolean>>): String {
    val failed = results.filter { !it.second }.map { it.first }
    return if (failed.isEmpty()) AppText.get(R.string.send_all_done, name, results.size)
    else AppText.get(R.string.send_all_some_failed, name, results.size - failed.size, results.size, joinList(failed))
}
