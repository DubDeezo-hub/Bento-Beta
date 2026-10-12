package com.example.pssender // change to match your project's package name

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// "Install from this phone": the packages stay on the phone and the console downloads them over Wi-Fi.
// ---------------------------------------------------------------------------------------------

/** A file the person picked to share. [size] is -1 when it can't be told; such a file can't be shared. */
private class PickedFile(val uri: Uri, val name: String, val size: Long)

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
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(str(R.string.serve_choose)) }
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
                    val items = picked.map { ServeItem(it.name, it.size, PhoneFileSource(ctx.contentResolver, it.uri)) }
                    ServeService.start(ctx, ServeRequest(consoleName, host.trim(), installPort.trim(), items))
                }
            ) { Text(str(R.string.serve_start)) }
            if (info.message.isNotEmpty()) {
                Text(info.message, color = sc.warn, style = MaterialTheme.typography.bodySmall)
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
