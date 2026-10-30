package com.example.pssender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// "Install from this phone": the packages stay on the phone and the console downloads them over Wi-Fi.
// ---------------------------------------------------------------------------------------------

/** A file the person picked to share. [size] is -1 when it can't be told; such a file can't be shared. */
private class PickedFile(val uri: Uri, val name: String, val size: Long)

/** At most this many packages are taken from one folder. */
private const val SERVE_FOLDER_MAX = 50

/** The last few sets of packages installed from this phone, kept so a set can be picked again. */
object ServeHistory {
    private const val KEY = "serve_history"
    private const val KEEP = 8

    class Entry(val name: String, val uri: String, val size: Long)
    class Batch(val time: Long, val files: List<Entry>) {
        fun label(): String {
            val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(time))
            val names = files.take(2).joinToString(", ") { it.name } + if (files.size > 2) " +" + (files.size - 2) else ""
            return "$date · $names"
        }
    }

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    fun load(ctx: android.content.Context): List<Batch> = try {
        val arr = org.json.JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = o.optJSONArray("files") ?: return@mapNotNull null
            Batch(o.optLong("time"), (0 until f.length()).mapNotNull { j ->
                val e = f.optJSONObject(j) ?: return@mapNotNull null
                Entry(e.optString("name"), e.optString("uri"), e.optLong("size"))
            })
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(ctx: android.content.Context, files: List<Entry>) {
        if (files.isEmpty()) return
        // The same set again moves to the top instead of being listed twice
        val same = files.map { it.uri }.toSet()
        val list = listOf(Batch(System.currentTimeMillis(), files)) + load(ctx).filter { b -> b.files.map { it.uri }.toSet() != same }
        val arr = org.json.JSONArray()
        list.take(KEEP).forEach { b ->
            val f = org.json.JSONArray()
            b.files.forEach { e -> f.put(org.json.JSONObject().put("name", e.name).put("uri", e.uri).put("size", e.size)) }
            arr.put(org.json.JSONObject().put("time", b.time).put("files", f))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}

/** What to say about one shared file: whether the console has asked for it yet, how far it got, or that it is done. */
private fun fileStatus(f: ServeFileInfo): String = when {
    f.problem -> AppText.get(R.string.serve_unreadable)
    f.done -> AppText.get(R.string.serve_file_done)
    f.covered > 0L -> {
        val percent = if (f.size > 0L) (f.covered * 100 / f.size).toInt().coerceIn(0, 100) else 0
        val detail = AppText.get(
            R.string.transfer_detail_total,
            AppText.get(R.string.percent_value, percent), fmtBytes(f.covered), fmtBytes(f.size)
        )
        AppText.get(R.string.serve_file_pulling, detail)
    }
    f.installOk == null -> AppText.get(R.string.status_sending)
    else -> AppText.get(R.string.serve_file_waiting)
}

private fun copyLink(ctx: Context, url: String) {
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("link", url))
}

/**
 * The section of the Install tab for sharing packages from the phone. [consoleName] and [host] say which console is
 * told to install them, [installPort] is where its installer listens, and [onBeforeStart] runs when sharing begins (it
 * asks for permission to show the notification).
 */
@Composable
fun ServeSection(consoleName: String, host: String, installPort: String, onBeforeStart: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val sc = statusColors()
    val info by ServeState.info.collectAsState()
    var picked by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var copied by remember { mutableStateOf(-1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            picked = uris.map { u ->
                keepAccess(ctx.contentResolver, u)
                PickedFile(u, ctx.contentResolver.displayName(u), exactSize(ctx.contentResolver, u))
            }
        }
    }
    // A whole folder of packages: every .pkg in it and its folders, in name order
    val scope = rememberCoroutineScope()
    var folderNote by remember { mutableStateOf("") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            folderNote = AppText.get(R.string.serve_folder_looking)
            val found = withContext(Dispatchers.IO) {
                try {
                    keepTreeAccess(ctx.contentResolver, tree)
                    walkPhoneTree(ctx.contentResolver, tree).files.filter { it.rel.endsWith(".pkg", true) }.sortedBy { it.rel.lowercase() }
                } catch (_: Exception) {
                    null
                }
            }
            folderNote = when {
                found == null -> AppText.get(R.string.serve_folder_failed)
                found.isEmpty() -> AppText.get(R.string.serve_folder_none)
                else -> ""
            }
            if (!found.isNullOrEmpty()) picked = found.take(SERVE_FOLDER_MAX).map { PickedFile(Uri.parse(it.uri), it.rel.substringAfterLast('/'), it.size) }
            if ((found?.size ?: 0) > SERVE_FOLDER_MAX) folderNote = AppText.get(R.string.serve_folder_capped, SERVE_FOLDER_MAX)
        }
    }
    var history by remember { mutableStateOf(ServeHistory.load(ctx)) }
    // "Link copied" goes back to "Copy link" after a moment
    LaunchedEffect(copied) {
        if (copied >= 0) {
            delay(2000)
            copied = -1
        }
    }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainerHigh)
            .border(1.dp, cs.outlineVariant, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(str(R.string.serve_title), style = MaterialTheme.typography.titleMedium)
        if (!info.running) {
            Text(str(R.string.serve_intro), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(str(R.string.serve_choose)) }
                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(str(R.string.serve_choose_folder)) }
            }
            if (folderNote.isNotEmpty()) Text(folderNote, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (picked.isEmpty()) {
                Text(str(R.string.serve_none))
            } else {
                picked.forEach { p ->
                    val second = if (p.size > 0L) fmtBytes(p.size) else AppText.get(R.string.serve_unreadable)
                    Text(
                        str(R.string.transfer_detail_done, p.name, second),
                        color = if (p.size > 0L) cs.onSurface else sc.bad,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Button(
                enabled = host.isNotBlank() && picked.isNotEmpty() && picked.all { it.size > 0L },
                onClick = {
                    onBeforeStart()
                    ServeHistory.add(ctx, picked.map { ServeHistory.Entry(it.name, it.uri.toString(), it.size) })
                    history = ServeHistory.load(ctx)
                    val items = picked.map { ServeItem(it.name, it.size, PhoneFileSource(ctx.contentResolver, it.uri)) }
                    ServeService.start(ctx, ServeRequest(consoleName, host.trim(), installPort.trim(), items))
                }
            ) { Text(str(R.string.serve_start)) }
            if (info.message.isNotEmpty()) {
                Text(info.message, color = sc.warn, style = MaterialTheme.typography.bodySmall)
            }
            // Earlier installs from this phone, one tap from being picked again
            if (history.isNotEmpty()) {
                Text(str(R.string.serve_history), style = MaterialTheme.typography.labelLarge)
                history.forEach { batch ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            batch.label(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            // A file that was moved or deleted since has no size any more, and is left out
                            val again = batch.files.mapNotNull { e ->
                                val u = Uri.parse(e.uri)
                                val size = try { exactSize(ctx.contentResolver, u) } catch (_: Exception) { -1L }
                                if (size > 0) PickedFile(u, e.name, size) else null
                            }
                            picked = again
                            folderNote = if (again.size < batch.files.size) AppText.get(R.string.serve_history_missing, batch.files.size - again.size) else ""
                        }) { Text(str(R.string.serve_history_again)) }
                    }
                }
            }
        } else {
            info.files.forEachIndexed { index, f ->
                val fraction = if (f.size > 0L) (f.covered.toFloat() / f.size.toFloat()).coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        f.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(str(R.string.percent_value, (fraction * 100).toInt()), style = MaterialTheme.typography.titleSmall)
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.primary,
                    trackColor = cs.surfaceVariant
                )
                Text(
                    fileStatus(f),
                    color = if (f.problem) sc.bad else cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                // The console may have refused the request; the link still works for installing by hand
                if (f.installOk == false && f.covered == 0L && !f.problem) {
                    Text(f.installText, color = sc.warn, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (f.url.isNotEmpty()) {
                    TextButton(onClick = {
                        copyLink(ctx, f.url)
                        copied = index
                    }) { Text(str(if (copied == index) R.string.serve_copied else R.string.serve_copy)) }
                }
            }
            val extra = speedAndTime(info.speed, info.secondsLeft)
            if (extra.isNotEmpty()) {
                Text(extra, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { ServeService.stop() }) { Text(str(R.string.serve_stop)) }
            Text(str(R.string.serve_hint), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
