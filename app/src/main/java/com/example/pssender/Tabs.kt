package com.example.pssender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// The Install and Upload tabs, and the text fields the screens share. The rest of the app's frame (tabs, the
// console card, the dialogs over everything) is in MainActivity.kt.
// ---------------------------------------------------------------------------------------------

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Like [Field], but the keyboard that comes up has numbers on it. */
@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** A text box for several lines: it shows [minLines] lines and grows to 8 as more are typed. */
@Composable
fun LinesField(label: String, value: String, minLines: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines, maxLines = 8,
        modifier = Modifier.fillMaxWidth()
    )
}

/** A small rounded button with a short text, for a list of things to pick from. */
@Composable
internal fun PickChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .widthIn(max = 220.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
fun InstallTab(
    host: String,
    port: String,
    onPortChange: (String) -> Unit,
    consoleName: String,
    links: String,
    changeLinks: ((String) -> String) -> Unit,
    onBeforeServe: () -> Unit,
    type: ConsoleType = ConsoleType.PS5,
    ftp: FtpSettings? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val idle = str(R.string.status_idle)
    var status by remember { mutableStateOf(idle) }
    var busy by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(RecentLinkStore.load(ctx)) }
    val found = linksIn(links)
    val leftOut = skippedWords(links).size
    // Looking inside packages: the links in the box, or a file on the phone
    var checkLinks by remember { mutableStateOf<List<String>?>(null) }
    var checkFile by remember { mutableStateOf<Uri?>(null) }
    val pkgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) checkFile = u }
    checkLinks?.let { l -> PkgInfoDialog(l.take(20), null, ftp, type) { checkLinks = null } }
    checkFile?.let { f -> PkgInfoDialog(emptyList(), f, ftp, type) { checkFile = null } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TipTitle(str(R.string.tip_install_links_title), Tip.INSTALL_LINKS)
        LinesField(str(R.string.install_links_label), links, 3) { v -> changeLinks { v } }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val onClipboard = linksIn(clipboardText(ctx))
                if (onClipboard.isEmpty()) {
                    status = AppText.get(R.string.install_paste_none)
                } else {
                    changeLinks { mergeLinks(it, onClipboard) }
                }
            }) { Text(str(R.string.install_paste)) }
            TextButton(enabled = links.isNotEmpty(), onClick = { changeLinks { "" } }) { Text(str(R.string.action_clear)) }
            TextButton(enabled = found.isNotEmpty(), onClick = { checkLinks = found }) { Text(str(R.string.meta_check)) }
        }
        if (leftOut > 0) {
            Text(str(R.string.install_links_skipped, leftOut), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.install_recent), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    RecentLinkStore.clear(ctx)
                    recent = emptyList()
                }) { Text(str(R.string.install_recent_clear)) }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recent.forEach { link -> PickChip(linkLabel(link)) { changeLinks { mergeLinks(it, listOf(link)) } } }
            }
        }
        Button(
            enabled = busy || (host.isNotBlank() && found.isNotEmpty()),
            onClick = {
                if (busy) {
                    stop = true // takes effect before the next link; the one being sent is finished
                } else {
                    val toSend = found
                    busy = true
                    stop = false
                    status = AppText.get(R.string.status_sending)
                    scope.launch {
                        val result = sendInstallBatch(
                            toSend,
                            send = { installRequest(host.trim(), port.trim(), it) },
                            onProgress = { number, total ->
                                if (total > 1) status = AppText.get(R.string.install_sending_n, number, total)
                            },
                            onAccepted = { link ->
                                // An accepted link leaves the box (what is left still needs sending) and joins the recent ones
                                changeLinks { removeLinks(it, setOf(link)) }
                                recent = RecentLinkStore.push(ctx, listOf(link))
                            },
                            cancelled = { stop }
                        )
                        status = batchMessage(result, toSend.size)
                        busy = false
                    }
                }
            }
        ) {
            Text(
                when {
                    busy -> str(R.string.action_cancel)
                    found.size > 1 -> str(R.string.action_send) + " (" + found.size + ")"
                    else -> str(R.string.action_send)
                }
            )
        }
        Text(status)
        HelpLink(status)
        TipTitle(str(R.string.tip_install_phone_title), Tip.INSTALL_PHONE)
        ServeSection(consoleName, host, port, onBeforeServe)
        TextButton(onClick = { pkgPicker.launch(arrayOf("*/*")) }) { Text(str(R.string.meta_check_phone)) }
        // Set once and then left alone: folded away
        Foldable(str(R.string.settings_section), Tip.INSTALL_PORT, summary = str(R.string.settings_port_summary, port)) {
            Field(str(R.string.install_port_label, type.installerName), port, onPortChange) // etaHEN's installer, or the RPI app on a PS4
        }
    }
}

@Composable
fun UploadTab(
    host: String,
    profile: Profile,
    update: ((Profile) -> Profile) -> Unit,
    pass: String,
    onPassChange: (String) -> Unit,
    onStart: (TransferRequest) -> Unit,
    settings: FtpSettings? = null,
    onStartMany: (List<TransferRequest>) -> Unit = { it.forEach(onStart) }
) {
    val ctx = LocalContext.current
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            for (u in picked) keepAccess(ctx.contentResolver, u)
            uris = picked
        }
    }
    val port = profile.ftpPort.trim().toIntOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TipTitle(str(R.string.tip_upload_title), Tip.UPLOAD)
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(str(R.string.upload_choose)) }
        Text(
            when (uris.size) {
                0 -> str(R.string.upload_none)
                1 -> ctx.contentResolver.displayName(uris[0])
                else -> str(R.string.upload_files_chosen, uris.size)
            }
        )
        Button(
            enabled = host.isNotBlank() && uris.isNotEmpty() && port != null,
            onClick = {
                if (port != null) {
                    // Every chosen file joins the queue; they are sent one after the other
                    for (u in uris) {
                        onStart(
                            TransferRequest(
                                name = ctx.contentResolver.displayName(u),
                                host = host.trim(),
                                port = port,
                                user = profile.ftpUser.trim(),
                                pass = pass,
                                dir = profile.ftpDir.trim(),
                                fileUri = u.toString()
                            )
                        )
                    }
                }
            }
        ) { Text(str(R.string.upload_start)) }
        FolderUploadRow(settings, profile, onStartMany)
        // Where the files go, in plain sight: a read-only folder here makes every upload fail
        val dir = profile.ftpDir.trim().ifEmpty { "/" }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(str(R.string.upload_goes_to, dir), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (dir.trimEnd('/') != "/data") {
                TextButton(onClick = { update { it.copy(ftpDir = "/data") } }) { Text(str(R.string.upload_use_data)) }
            }
        }
        Text(str(R.string.upload_help), style = MaterialTheme.typography.bodySmall)
        FolderSyncSection(settings, profile, onStartMany)
        Foldable(str(R.string.files_ftp_settings), Tip.FTP, summary = str(R.string.settings_ftp_summary, profile.ftpPort, profile.ftpDir)) {
            FtpSettingsFields(profile, update, pass, onPassChange)
        }
    }
}

/** Where a picked phone folder goes on the console: a folder of the same name inside the upload folder [ftpDir]. */
fun folderUploadRoot(ftpDir: String, folderName: String): String =
    joinPath(ftpDir.trim().ifEmpty { "/data" }, folderName.trim().ifEmpty { "Folder" })

/**
 * "Choose a folder": a whole folder from the phone, with the folders inside it, goes to the console. The folders are made
 * on the console first, then every file joins the queue (side by side, like several picked files).
 */
@Composable
private fun FolderUploadRow(settings: FtpSettings?, profile: Profile, onStartMany: (List<TransferRequest>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && settings != null) {
            keepTreeAccess(ctx.contentResolver, tree)
            busy = true
            status = AppText.get(R.string.upload_folder_reading)
            scope.launch {
                try {
                    val phone = withContext(Dispatchers.IO) { walkPhoneTree(ctx.contentResolver, tree) }
                    if (phone.files.isEmpty()) {
                        status = AppText.get(R.string.upload_folder_empty)
                    } else {
                        val root = folderUploadRoot(profile.ftpDir, phone.name)
                        withContext(Dispatchers.IO) { ftpMakeDirs(settings, root, phone.folders, makeTop = true) }
                        onStartMany(uploadRequests(settings, root, phone.files))
                        status = AppText.get(R.string.upload_folder_queued, phone.name, phone.files.size, root)
                    }
                } catch (_: SecurityException) {
                    status = AppText.get(R.string.sync_lost_access)
                } catch (e: Exception) {
                    status = ftpFailureMessage(e)
                } finally {
                    busy = false
                }
            }
        }
    }
    OutlinedButton(enabled = settings != null && !busy, onClick = { picker.launch(null) }) { Text(str(R.string.upload_choose_folder)) }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (status.isNotEmpty()) {
        Text(status, style = MaterialTheme.typography.bodySmall)
        HelpLink(status)
    }
}
