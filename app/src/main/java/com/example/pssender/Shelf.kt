package com.example.pssender // change to match your project's package name

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val scope = rememberCoroutineScope()
    var shelf by remember(shelfVersion) { mutableStateOf(shelfList(shelfDir(ctx))) }
    var oneOff by remember { mutableStateOf<Uri?>(null) }
    val idle = str(R.string.status_idle)
    var status by remember { mutableStateOf(idle) }
    var removing by remember { mutableStateOf<ShelfItem?>(null) }
    val canSend = host.isNotBlank() && port.trim().toIntOrNull() != null

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
                    shelf = shelfList(shelfDir(ctx))
                    removing = null
                }) { Text(str(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TipTitle(str(R.string.payload_shelf_title), Tip.PAYLOAD_SHELF, style = MaterialTheme.typography.titleMedium)
        if (shelf.isEmpty()) {
            Text(str(R.string.payload_shelf_empty, ActiveConsole.type.hen), style = MaterialTheme.typography.bodySmall)
        }
        shelf.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtBytes(item.size), style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = canSend,
                    onClick = {
                        status = AppText.get(R.string.payload_sending, item.name)
                        scope.launch {
                            val sent = sendPayloadFile(File(shelfDir(ctx), item.name), host.trim(), port.trim().toInt())
                            status = AppText.get(R.string.payload_result, item.name, sent)
                        }
                    }
                ) { Text(str(R.string.action_send)) }
                TextButton(onClick = { removing = item }) { Text(str(R.string.action_remove)) }
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
                status = AppText.get(R.string.status_sending)
                scope.launch { status = sendPayload(ctx.contentResolver, oneOff!!, host.trim(), port.trim().toInt()) }
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
