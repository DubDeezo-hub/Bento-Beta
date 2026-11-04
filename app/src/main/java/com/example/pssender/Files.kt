package com.example.pssender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// What the file browser shows. It lives in the main screen, so the listing survives tab switches.
// ---------------------------------------------------------------------------------------------

class FilesModel {
    /** Browsing, the games list or the saves. */
    var view by mutableStateOf(FilesView.BROWSE)
    var path by mutableStateOf("")
    var entries by mutableStateOf<List<FtpEntry>>(emptyList())
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")

    /** Bumped by [reset]; the results of jobs that started before it are thrown away. */
    private var generation = 0

    /** Forgets everything (used when another console is selected). */
    fun reset() {
        generation++
        ConsoleApps.forget()
        ConsoleSaves.forget()
        path = ""
        entries = emptyList()
        busy = false
        message = ""
    }

    /**
     * Opens the folder [target]. On failure the current folder stays on screen, with a message. [fallback] is another folder
     * to open when [target] can't be (the folder viewed last time may be gone) although the console does answer.
     */
    fun open(scope: CoroutineScope, s: FtpSettings, target: String, onError: () -> Unit = {}, fallback: String? = null) {
        if (busy) return
        val gen = generation
        busy = true
        message = AppText.get(R.string.files_loading)
        scope.launch {
            try {
                val listing = withContext(Dispatchers.IO) {
                    try {
                        ftpList(s, target)
                    } catch (e: Exception) {
                        if (fallback == null || fallback == target || !folderIsTheProblem(e)) throw e
                        ftpList(s, fallback)
                    }
                }
                if (gen == generation) {
                    path = listing.path
                    entries = listing.entries
                    message = if (listing.entries.isEmpty()) AppText.get(R.string.files_empty) else ""
                }
            } catch (e: Exception) {
                if (gen == generation) {
                    message = ftpFailureMessage(e)
                    onError()
                }
            } finally {
                if (gen == generation) busy = false
            }
        }
    }

    /**
     * Looks through the folder being viewed for what downloading [chosen] (folders included) comes to, and hands the result to
     * [onPlan] (not when another console was selected meanwhile). A problem is shown as the message.
     */
    fun plan(scope: CoroutineScope, s: FtpSettings, chosen: List<FtpEntry>, onPlan: (DownloadPlan) -> Unit) {
        if (busy) return
        val gen = generation
        val here = path
        busy = true
        message = AppText.get(R.string.files_dl_planning)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ftpPlanDownload(s, here, chosen) }
                if (gen == generation) {
                    message = ""
                    onPlan(result)
                }
            } catch (e: Exception) {
                if (gen == generation) message = ftpFailureMessage(e)
            } finally {
                if (gen == generation) busy = false
            }
        }
    }

    /** Runs a change (rename, delete, new folder), then reloads the folder. [job] returns the message to show. */
    fun change(scope: CoroutineScope, s: FtpSettings, label: String, job: () -> String) {
        if (busy) return
        val gen = generation
        val here = path
        busy = true
        message = label
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { job() }
            } catch (e: Exception) {
                ftpFailureMessage(e)
            }
            try {
                val listing = withContext(Dispatchers.IO) { ftpList(s, here) }
                if (gen == generation) {
                    path = listing.path
                    entries = listing.entries
                }
            } catch (_: Exception) {
            }
            if (gen == generation) {
                message = result
                busy = false
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------

/** The FTP login fields (port, user, password, start folder). Shared by the Upload tab and the Files tab. */
@Composable
fun FtpSettingsFields(
    profile: Profile,
    update: ((Profile) -> Profile) -> Unit,
    pass: String,
    onPassChange: (String) -> Unit,
    viewedFolder: String? = null
) {
    Field(str(R.string.ftp_port), profile.ftpPort) { v -> update { it.copy(ftpPort = v) } }
    Field(str(R.string.ftp_user), profile.ftpUser) { v -> update { it.copy(ftpUser = v) } }
    Field(str(R.string.ftp_password), pass, onPassChange)
    Field(str(R.string.ftp_folder), profile.ftpDir) { v -> update { it.copy(ftpDir = v) } }
    if (viewedFolder != null && viewedFolder != profile.ftpDir) {
        TextButton(onClick = { update { it.copy(ftpDir = viewedFolder) } }) { Text(str(R.string.ftp_use_folder, viewedFolder)) }
    }
}

/** The text of a way to sort. */
@Composable
private fun sortLabel(by: SortBy): String =
    str(
        when (by) {
            SortBy.NAME -> R.string.files_sort_name
            SortBy.SIZE -> R.string.files_sort_size
            SortBy.NEWEST -> R.string.files_sort_newest
        }
    )

/**
 * One line of the list. Tapping it opens it (or ticks it, while entries are being picked); holding it starts picking.
 * While picking, a box is shown instead of the menu button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    e: FtpEntry,
    picking: Boolean,
    ticked: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onMenu: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // A capsule: a round icon (folders lit red), the name and details, and the menu
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(if (ticked) cs.primaryContainer else cs.surfaceContainer)
            .combinedClickable(onClick = onTap, onLongClick = onHold)
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (picking) Checkbox(checked = ticked, onCheckedChange = { onTap() })
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (e.isDir) bloodGradient else Brush.linearGradient(listOf(cs.surface, cs.surface))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (e.isDir) FilesIcon else if (e.isLink) WebIcon else FileIcon,
                contentDescription = null,
                tint = if (e.isDir) Color.White else cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            val detail = entryDetail(e)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (!picking) TextButton(onClick = onMenu) { Text("⋮") }
    }
}

/**
 * Browse the console's files over FTP: open folders, rename, delete, create folders, upload into the folder
 * being viewed, and download files and whole folders to the phone (downloads run in the background like uploads do).
 * Holding an entry picks it and lets more be picked, to download or delete them together. The list can be sorted and
 * searched, folders can be pinned for a quick way back, and the folder viewed last is opened again next time. Pulling the
 * list down loads the folder again.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilesTab(
    model: FilesModel,
    scope: CoroutineScope,
    settings: FtpSettings?,
    profile: Profile,
    pass: String,
    onPassChange: (String) -> Unit,
    onUpdateProfile: ((Profile) -> Profile) -> Unit,
    transferActive: Boolean,
    onStart: (TransferRequest) -> Unit,
    onStartMany: (List<TransferRequest>) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var menuFor by remember { mutableStateOf<FtpEntry?>(null) }
    var sizesFor by remember { mutableStateOf<String?>(null) }
    var dupesFor by remember { mutableStateOf<String?>(null) }
    var speedTest by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var zipping by remember { mutableStateOf(false) }
    var zipCancel by remember { mutableStateOf(false) }
    var showEtaHen by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<FtpEntry?>(null) }
    var deleteFor by remember { mutableStateOf<FtpEntry?>(null) }
    var deleteMany by remember { mutableStateOf<List<FtpEntry>?>(null) }
    var downloadPlan by remember { mutableStateOf<DownloadPlan?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(FilesStore.sort(ctx)) }
    var pins by remember(profile.id) { mutableStateOf(FilesStore.pins(ctx, profile.id)) }
    var gonePin by remember { mutableStateOf<String?>(null) }
    var selected by remember(profile.id) { mutableStateOf<Set<String>>(emptySet()) }
    val startFolder = profile.ftpDir.trim().ifEmpty { "/" }
    val ready = settings != null && !model.busy
    val hasFolder = model.path.isNotEmpty()
    val shown = remember(model.entries, sortBy, query) { arrangeEntries(model.entries, sortBy, query) }
    val picking = selected.isNotEmpty()
    val chosen = chosenEntries(selected, model.entries)
    val allShownTicked = shown.isNotEmpty() && shown.all { it.name in selected }
    val pinned = hasFolder && isPinned(pins, model.path)

    // Reload the current folder; the first time (or after another console was selected) the folder viewed last for this
    // console is opened, and the start folder when that one is gone
    val reload: () -> Unit = {
        if (settings != null) {
            if (model.path.isEmpty()) {
                val last = startingFolder(FilesStore.lastFolder(ctx, profile.id), startFolder)
                model.open(scope, settings, last, fallback = startFolder)
            } else {
                model.open(scope, settings, model.path)
            }
        }
    }

    // Every time the tab is shown (or another console is selected) the listing is refreshed
    LaunchedEffect(profile.id) { reload() }

    // Pulling the list down loads the folder again. Its spinner turns while that reload runs and stops when the listing is
    // in; the bar under the buttons is for everything else that keeps the tab busy.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(model.busy) {
        if (!model.busy) pulled = false
    }
    val pullToReload: () -> Unit = {
        if (ready) {
            pulled = true
            reload()
        }
    }

    // ...and again when a transfer finishes, so a new upload appears in the list
    var wasActive by remember { mutableStateOf(transferActive) }
    LaunchedEffect(transferActive) {
        if (wasActive && !transferActive && model.path.isNotEmpty()) reload()
        wasActive = transferActive
    }

    // The folder being viewed is remembered for the next time
    LaunchedEffect(profile.id, model.path) {
        if (model.path.isNotEmpty()) FilesStore.saveLastFolder(ctx, profile.id, model.path)
    }

    // Another folder starts without ticks and without a search; after a change only the entries still there stay ticked
    LaunchedEffect(model.path) {
        selected = emptySet()
        query = ""
        searching = false
    }
    LaunchedEffect(model.entries) {
        if (selected.isNotEmpty()) selected = stillThere(selected, model.entries)
    }

    // The system Back button goes up one folder, or ends picking first
    BackHandler(enabled = model.view == FilesView.BROWSE && settings != null && model.path.isNotEmpty() && model.path != "/" && !model.busy) {
        if (settings != null) model.open(scope, settings, parentPath(model.path))
    }
    BackHandler(enabled = picking) { selected = emptySet() }
    // ...and from the games list or the saves back to the folders
    BackHandler(enabled = model.view != FilesView.BROWSE) { model.view = FilesView.BROWSE }

    // Puts these files in the download queue
    val queueDownloads: (List<PlannedFile>) -> Unit = { files ->
        if (settings != null && files.isNotEmpty()) {
            onStartMany(
                files.map { f ->
                    TransferRequest(
                        name = f.name,
                        host = settings.host, port = settings.port, user = settings.user, pass = settings.pass,
                        dir = f.saveIn, // for a download: the folder inside Downloads/PS5 (or Downloads/PS4)
                        remotePath = f.remotePath,
                        remoteSize = f.size
                    )
                }
            )
        }
    }

    // Files go straight into the queue; with folders among them the console is looked through first and the person confirms
    val startDownload: (List<FtpEntry>) -> Unit = { list ->
        if (settings != null && list.isNotEmpty()) {
            if (list.none { it.isDir }) {
                queueDownloads(list.map { PlannedFile(joinPath(model.path, it.name), "", it.name, it.size) })
                selected = emptySet()
            } else {
                model.plan(scope, settings, list) { downloadPlan = it }
            }
        }
    }

    val askDelete: (List<FtpEntry>) -> Unit = { list ->
        if (list.size == 1) deleteFor = list[0] else if (list.size > 1) deleteMany = list
    }

    // A zip on the phone, unpacked onto the console as a folder named after it
    val zipUpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val at = model.path
        if (uri != null && settings != null && at.isNotEmpty()) {
            val folder = safeLocalName(ctx.contentResolver.displayName(uri).replace(Regex("(?i)\\.zip$"), ""))
            zipping = true
            zipCancel = false
            scope.launch {
                model.message = AppText.get(R.string.zipup_progress, 0, fmtBytes(0))
                model.message = withContext(Dispatchers.IO) {
                    try {
                        var shown = 0L
                        val n = ftpUploadZip(ctx.contentResolver, settings, uri, at, folder,
                            progress = { f, b ->
                                val now = System.currentTimeMillis()
                                if (now - shown > 250) {
                                    shown = now
                                    model.message = AppText.get(R.string.zipup_progress, f, fmtBytes(b))
                                }
                            },
                            cancelled = { zipCancel })
                        AppText.get(R.string.zipup_done, n, joinPath(at, folder))
                    } catch (e: Exception) {
                        ftpFailureMessage(e)
                    }
                }
                zipping = false
                reload()
            }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty() && settings != null && model.path.isNotEmpty()) {
            for (file in picked) {
                keepAccess(ctx.contentResolver, file)
                onStart(
                    TransferRequest(
                        name = ctx.contentResolver.displayName(file),
                        host = settings.host, port = settings.port, user = settings.user, pass = settings.pass,
                        dir = model.path,
                        fileUri = file.toString()
                    )
                )
            }
        }
    }

    // ---- dialogs ----

    if (speedTest && settings != null) SpeedTestDialog(settings) { speedTest = false }

    dupesFor?.let { path ->
        if (settings != null) DuplicatesDialog(settings, path, onClose = {
            dupesFor = null
            reload()
        })
    }

    sizesFor?.let { path ->
        if (settings != null) FolderSizesDialog(settings, path, onOpen = { sub ->
            sizesFor = null
            model.open(scope, settings, sub)
        }, onClose = { sizesFor = null })
    }

    menuFor?.let { e ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (e.isDir) str(R.string.files_kind_folder) else if (e.isLink) str(R.string.files_kind_link, fmtBytes(e.size)) else fmtBytes(e.size),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            menuFor = null
                            startDownload(listOf(e))
                        }
                    ) { Text(str(R.string.files_download)) }
                    // A folder as one zip on the phone (a game's saves, a set of screenshots)
                    if (e.isDir && settings != null) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !zipping,
                            onClick = {
                                menuFor = null
                                zipping = true
                                zipCancel = false
                                scope.launch {
                                    model.message = AppText.get(R.string.zip_planning)
                                    model.message = withContext(Dispatchers.IO) {
                                        try {
                                            val plan = ftpPlanDownload(settings, model.path, listOf(e))
                                            val total = plan.totalBytes
                                            // The zip is about as big as the files (game data barely shrinks): don't start one that can't fit
                                            val free = try { android.os.Environment.getExternalStorageDirectory().usableSpace } catch (_: Exception) { Long.MAX_VALUE }
                                            if (plan.files.isEmpty()) AppText.get(R.string.zip_empty)
                                            else if (total > free) AppText.get(R.string.zip_no_space, fmtBytes(total), fmtBytes(free))
                                            else {
                                                var shown = 0L
                                                val where = ftpZipToPhone(ctx.contentResolver, settings, plan, safeLocalName(e.name) + ".zip",
                                                    progress = { n, b ->
                                                        val now = System.currentTimeMillis()
                                                        if (now - shown > 250 || n == plan.files.size) {
                                                            shown = now
                                                            model.message = AppText.get(R.string.zip_progress, n, plan.files.size, fmtBytes(b), fmtBytes(total))
                                                        }
                                                    },
                                                    cancelled = { zipCancel })
                                                AppText.get(R.string.zip_done, where) + if (plan.truncated) "\n" + AppText.get(R.string.zip_truncated) else ""
                                            }
                                        } catch (x: Exception) {
                                            failedText(x)
                                        }
                                    }
                                    zipping = false
                                }
                            }
                        ) { Text(str(R.string.zip_download)) }
                    }
                    if (!e.isDir && !e.isLink && isEditableText(e.name) && e.size <= TEXT_EDIT_MAX) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                menuFor = null
                                editing = joinPath(model.path, e.name)
                            }
                        ) { Text(str(R.string.text_edit_open)) }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            menuFor = null
                            renameFor = e
                        }
                    ) { Text(str(R.string.action_rename)) }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            menuFor = null
                            deleteFor = e
                        }
                    ) { Text(str(R.string.action_delete)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text(str(R.string.action_close)) } }
        )
    }

    editing?.let { p ->
        if (settings != null) TextFileDialog(settings, p) {
            editing = null
            model.open(scope, settings, model.path)
        } else editing = null
    }
    if (showEtaHen && settings != null) EtaHenSettingsDialog(settings) { showEtaHen = false }

    renameFor?.let { e ->
        var newName by remember(e) { mutableStateOf(e.name) }
        val problem = badRemoteName(newName)
            ?: if (newName != e.name && model.entries.any { it.name == newName }) str(R.string.files_name_used) else null
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(str(R.string.action_rename)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(str(R.string.files_new_name), newName) { newName = it }
                    if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = problem == null && newName != e.name,
                    onClick = {
                        renameFor = null
                        if (settings != null) {
                            val dir = model.path
                            model.change(scope, settings, AppText.get(R.string.files_renaming)) {
                                ftpRename(settings, dir, e.name, newName)
                                AppText.get(R.string.files_renamed, e.name, newName)
                            }
                        }
                    }
                ) { Text(str(R.string.action_rename)) }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(str(R.string.files_delete_title, e.name)) },
            text = { Text(str(if (e.isDir) R.string.files_delete_folder else R.string.files_delete_file)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    selected = emptySet()
                    if (settings != null) {
                        val dir = model.path
                        model.change(scope, settings, AppText.get(R.string.files_deleting)) {
                            val removed = ftpDelete(settings, dir, e)
                            if (e.isDir && removed > 1) {
                                AppText.get(R.string.files_deleted_inside, e.name, removed - 1)
                            } else {
                                AppText.get(R.string.files_deleted, e.name)
                            }
                        }
                    }
                }) { Text(str(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    deleteMany?.let { list ->
        AlertDialog(
            onDismissRequest = { deleteMany = null },
            title = { Text(str(R.string.files_delete_many_title, list.size)) },
            text = { Text(str(R.string.files_delete_many_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteMany = null
                    selected = emptySet()
                    if (settings != null) {
                        val dir = model.path
                        model.change(scope, settings, AppText.get(R.string.files_deleting)) {
                            deleteSummaryText(ftpDeleteMany(settings, dir, list))
                        }
                    }
                }) { Text(str(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteMany = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    downloadPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { downloadPlan = null },
            title = { Text(str(R.string.files_dl_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (plan.files.isEmpty()) {
                        Text(str(R.string.files_dl_none))
                    } else {
                        Text(str(R.string.files_dl_confirm_text, plan.files.size, fmtBytes(plan.totalBytes), profile.type.model))
                    }
                    if (plan.skippedLinks > 0) Text(str(R.string.files_dl_links, plan.skippedLinks), style = MaterialTheme.typography.bodySmall)
                    if (plan.truncated) Text(str(R.string.files_dl_truncated, plan.files.size), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = plan.files.isNotEmpty(),
                    onClick = {
                        downloadPlan = null
                        selected = emptySet()
                        queueDownloads(plan.files)
                    }
                ) { Text(str(R.string.files_download)) }
            },
            dismissButton = { TextButton(onClick = { downloadPlan = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    if (showNewFolder) {
        var folderName by remember { mutableStateOf("") }
        val problem = if (folderName.isEmpty()) null else badRemoteName(folderName)
            ?: if (model.entries.any { it.name == folderName }) str(R.string.files_name_used) else null
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text(str(R.string.files_new_folder)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(str(R.string.files_folder_name), folderName) { folderName = it }
                    if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = folderName.isNotEmpty() && problem == null,
                    onClick = {
                        showNewFolder = false
                        if (settings != null) {
                            val dir = model.path
                            model.change(scope, settings, AppText.get(R.string.files_creating)) {
                                ftpMakeDir(settings, dir, folderName)
                                AppText.get(R.string.files_created, folderName)
                            }
                        }
                    }
                ) { Text(str(R.string.action_create)) }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text(str(R.string.action_cancel)) } }
        )
    }

    if (showSort) {
        val choose: (SortBy) -> Unit = { by ->
            sortBy = by
            FilesStore.saveSort(ctx, by)
            showSort = false
        }
        AlertDialog(
            onDismissRequest = { showSort = false },
            title = { Text(str(R.string.files_sort_title)) },
            text = {
                Column {
                    for (by in SortBy.values()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { choose(by) }.padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = sortBy == by, onClick = { choose(by) })
                            Text(sortLabel(by))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSort = false }) { Text(str(R.string.action_close)) } }
        )
    }

    // A pinned folder that could not be opened: the pin can be taken off
    gonePin?.let { folder ->
        AlertDialog(
            onDismissRequest = { gonePin = null },
            title = { Text(folder, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(model.message) },
            confirmButton = {
                TextButton(onClick = {
                    gonePin = null
                    pins = unpinFolder(pins, folder)
                    FilesStore.savePins(ctx, profile.id, pins)
                }) { Text(str(R.string.files_unpin)) }
            },
            dismissButton = { TextButton(onClick = { gonePin = null }) { Text(str(R.string.action_close)) } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(str(R.string.files_ftp_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FtpSettingsFields(profile, onUpdateProfile, pass, onPassChange, model.path.ifEmpty { null })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    // The start folder may have been changed: the folder viewed last no longer decides where the tab opens
                    FilesStore.saveLastFolder(ctx, profile.id, "")
                    model.reset()
                    reload()
                }) { Text(str(R.string.action_done)) }
            }
        )
    }

    // ---- screen ----

    // Games, saves and the folder browser share the tab
    val openFolder: (String) -> Unit = { folder ->
        model.view = FilesView.BROWSE
        if (settings != null) model.open(scope, settings, folder)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipRow {
            Chip(str(R.string.files_view_browse), model.view == FilesView.BROWSE) { model.view = FilesView.BROWSE }
            Chip(str(R.string.files_view_apps), model.view == FilesView.APPS) { model.view = FilesView.APPS }
            Chip(str(R.string.files_view_saves), model.view == FilesView.SAVES) { ConsoleSaves.only = ""; model.view = FilesView.SAVES }
        }
        if (model.view == FilesView.APPS) {
            AppsView(settings, onOpenFolder = openFolder, onSaves = { id ->
                ConsoleSaves.only = id
                model.view = FilesView.SAVES
            }, modifier = Modifier.fillMaxWidth().weight(1f))
        } else if (model.view == FilesView.SAVES) {
            SavesView(settings, onOpenFolder = openFolder, onStartMany = onStartMany, modifier = Modifier.fillMaxWidth().weight(1f))
        } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (hasFolder) model.path else str(R.string.files_not_loaded),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TipButton(Tip.FILES)
        }
        if (picking) {
            Text(str(R.string.files_selected, selected.size), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { selected = emptySet() }) { Text(str(R.string.action_cancel)) }
                TextButton(enabled = shown.isNotEmpty(), onClick = { selected = allOrNone(selected, shown) }) {
                    Text(str(if (allShownTicked) R.string.files_select_none else R.string.files_select_all))
                }
                Button(enabled = ready, onClick = { startDownload(chosen) }) { Text(str(R.string.files_download)) }
                Button(enabled = ready, onClick = { askDelete(chosen) }) { Text(str(R.string.action_delete)) }
            }
        } else {
            // The buttons wrap onto a second line when a language needs more room than the screen is wide
            var tools by rememberSaveable { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = ready && hasFolder && model.path != "/",
                    onClick = { if (settings != null) model.open(scope, settings, parentPath(model.path)) }
                ) { Text(str(R.string.files_up)) }
                TextButton(enabled = ready, onClick = { reload() }) { Text(str(R.string.files_refresh)) }
                TextButton(enabled = ready && hasFolder, onClick = { showNewFolder = true }) { Text(str(R.string.files_new_folder)) }
                TextButton(enabled = hasFolder, onClick = { showSort = true }) { Text(str(R.string.files_sort)) }
                TextButton(
                    enabled = hasFolder,
                    onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }
                ) { Text(str(R.string.files_search)) }
                // The less-used actions fold away, so the folder's list keeps the room (small phones above all)
                TextButton(onClick = { tools = !tools }) { Text(if (tools) "⋯ ▴" else "⋯", fontWeight = FontWeight.ExtraBold) }
            }
            val labels = pinLabels(pins)
            // The folded actions get at most this much height and scroll inside it, so on a small phone the folder's
            // list below still has room (before, the list was squeezed to nothing and later rows were cut off)
            if (tools) Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
            ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = ready && hasFolder,
                    onClick = { uploadPicker.launch(arrayOf("*/*")) }
                ) { Text(str(R.string.files_upload_here)) }
                TextButton(
                    enabled = hasFolder && (pinned || pins.size < PINS_MAX),
                    onClick = {
                        pins = if (pinned) unpinFolder(pins, model.path) else pinFolder(pins, model.path)
                        FilesStore.savePins(ctx, profile.id, pins)
                    }
                ) { Text(str(if (pinned) R.string.files_unpin else R.string.files_pin)) }
                TextButton(onClick = { showSettings = true }) { Text(str(R.string.files_ftp_settings)) }
                TextButton(enabled = ready && hasFolder, onClick = { sizesFor = model.path }) { Text(str(R.string.files_sizes)) }
                TextButton(enabled = ready && hasFolder, onClick = { dupesFor = model.path }) { Text(str(R.string.files_dupes)) }
                TextButton(enabled = ready, onClick = { speedTest = true }) { Text(str(R.string.files_speed)) }
                TextButton(enabled = ready && hasFolder && !zipping, onClick = {
                    zipUpPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }) { Text(str(R.string.files_upload_zip)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                // Screenshots and video clips: where the console keeps what the Create button captured
                OutlinedButton(
                    enabled = ready,
                    onClick = { if (settings != null) model.open(scope, settings, ConsolePaths.MEDIA) }
                ) { Text(str(R.string.files_media), maxLines = 1) }
                // New captures straight to the phone (each one only once)
                OutlinedButton(
                    enabled = ready,
                    onClick = {
                        if (settings != null) scope.launch {
                            model.message = AppText.get(R.string.captures_looking)
                            val plan = withContext(Dispatchers.IO) {
                                try { CaptureSync.plan(ctx, settings, profile.id, firstTimeAll = true) } catch (e: Exception) { null }
                            }
                            model.message = when {
                                plan == null -> AppText.get(R.string.captures_failed)
                                plan.isEmpty() -> AppText.get(R.string.captures_none)
                                else -> {
                                    queueDownloads(plan)
                                    CaptureSync.markCopied(ctx, profile.id, plan.map { it.remotePath })
                                    AppText.get(R.string.captures_queued, plan.size, CaptureSync.FOLDER)
                                }
                            }
                        }
                    }
                ) { Text(str(R.string.captures_copy), maxLines = 1) }
                TipButton(Tip.MEDIA, modifier = Modifier.align(Alignment.CenterVertically))
                // etaHEN's own settings file, as switches (PS5)
                if (profile.type == ConsoleType.PS5) {
                    OutlinedButton(enabled = ready, onClick = { showEtaHen = true }) { Text(str(R.string.etahen_title), maxLines = 1) }
                }
            }
            run {
                var auto by remember(profile.id) { mutableStateOf(CaptureSync.autoOn(ctx, profile.id)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.captures_auto), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = {
                        auto = it
                        CaptureSync.setAuto(ctx, profile.id, it)
                    })
                }
            }
            }
            if (pins.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pins.forEachIndexed { i, folder ->
                        OutlinedButton(
                            enabled = ready,
                            onClick = { if (settings != null) model.open(scope, settings, folder, onError = { gonePin = folder }) }
                        ) { Text("📌 " + labels[i], maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            if (searching) Field(str(R.string.files_search_hint), query) { query = it }
        }
        if (model.busy && !pulled) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (settings == null) {
            Text(str(R.string.files_need_ip), style = MaterialTheme.typography.bodySmall)
        } else if (model.message.isNotEmpty()) {
            Text(model.message, style = MaterialTheme.typography.bodySmall)
            HelpLink(model.message)
            if (zipping) TextButton(onClick = { zipCancel = true }) { Text(str(R.string.action_cancel)) }
        }
        if (query.isNotBlank() && model.entries.isNotEmpty() && shown.isEmpty()) {
            Text(str(R.string.files_no_match), style = MaterialTheme.typography.bodySmall)
        }
        // Pulling down only starts when the list is at its top, so scrolling up the list never reloads it
        PullToRefreshBox(
            isRefreshing = pulled && model.busy,
            onRefresh = pullToReload,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown) { e ->
                    EntryRow(
                        e,
                        picking = picking,
                        ticked = e.name in selected,
                        onTap = {
                            if (picking) {
                                selected = toggled(selected, e.name)
                            } else if (settings != null && !model.busy) {
                                when {
                                    e.isDir -> model.open(scope, settings, joinPath(model.path, e.name))
                                    // A link might lead to a folder: try it, and offer the actions if it isn't one
                                    e.isLink -> model.open(scope, settings, joinPath(model.path, e.name), onError = { menuFor = e })
                                    else -> menuFor = e
                                }
                            }
                        },
                        onHold = { selected = toggled(selected, e.name) },
                        onMenu = { menuFor = e }
                    )
                }
            }
        }
    }
    }
}


/** What each folder (and the loose files) directly inside [path] takes on the console, biggest first; tap one to open it. */
@Composable
private fun FolderSizesDialog(s: FtpSettings, path: String, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var rows by remember(path) { mutableStateOf<List<SizeRow>?>(null) }
    var partial by remember(path) { mutableStateOf(false) }
    var problem by remember(path) { mutableStateOf("") }
    LaunchedEffect(path) {
        try {
            val tree = withContext(Dispatchers.IO) { ftpTree(s, path, FOLDER_SIZES_MAX) }
            partial = tree.truncated
            rows = sizesByChild(tree.files, tree.folders)
        } catch (e: Exception) {
            problem = ftpFailureMessage(e)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.files_sizes_title, path), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val r = rows
                when {
                    problem.isNotEmpty() -> Text(problem, style = MaterialTheme.typography.bodySmall)
                    r == null -> {
                        Text(str(R.string.files_sizes_counting), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    r.isEmpty() -> Text(str(R.string.files_sizes_empty), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(str(R.string.files_sizes_total, fmtBytes(r.sumOf { it.bytes })), style = MaterialTheme.typography.titleSmall)
                        if (partial) Text(str(R.string.files_sizes_partial, FOLDER_SIZES_MAX), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        val biggest = r.first().bytes.coerceAtLeast(1L)
                        for (row in r) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = row.name.isNotEmpty()) { onOpen(joinPath(path, row.name)) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (row.name.isEmpty()) str(R.string.files_sizes_loose) else row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    LinearProgressIndicator(progress = { row.bytes.toFloat() / biggest }, modifier = Modifier.fillMaxWidth())
                                }
                                Text(fmtBytes(row.bytes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}


/** Files under [path] that look like copies of each other (same name and size), each copy deletable after a yes. */
@Composable
private fun DuplicatesDialog(s: FtpSettings, path: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var groups by remember(path) { mutableStateOf<List<DuplicateGroup>?>(null) }
    var partial by remember(path) { mutableStateOf(false) }
    var message by remember(path) { mutableStateOf("") }
    var asking by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        try {
            val tree = withContext(Dispatchers.IO) { ftpTree(s, path, FOLDER_SIZES_MAX) }
            partial = tree.truncated
            groups = duplicateGroups(tree.files)
        } catch (e: Exception) {
            message = ftpFailureMessage(e)
        }
    }
    asking?.let { rel ->
        val name = rel.substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(str(R.string.files_delete_title, name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(rel + "\n\n" + str(R.string.files_delete_file), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    scope.launch {
                        message = try {
                            val full = joinPath(path, rel)
                            withContext(Dispatchers.IO) { ftpDelete(s, parentPath(full), FtpEntry(name, isDir = false, isLink = false, size = 0L)) }
                            // The copy is gone from the list; a group with one file left is no longer a duplicate
                            groups = groups.orEmpty().map { g -> g.copy(paths = g.paths - rel) }.filter { it.paths.size > 1 }
                            AppText.get(R.string.files_deleted, name)
                        } catch (e: Exception) {
                            ftpFailureMessage(e)
                        }
                    }
                }) { Text(str(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(str(R.string.action_cancel)) } }
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.files_dupes_title, path), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val g = groups
                if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
                when {
                    g == null && message.isEmpty() -> {
                        Text(str(R.string.files_sizes_counting), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    g == null -> {}
                    g.isEmpty() -> Text(str(R.string.files_dupes_none), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(str(R.string.files_dupes_extra, fmtBytes(g.sumOf { it.extra })), style = MaterialTheme.typography.titleSmall)
                        Text(str(R.string.files_dupes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (group in g) {
                            Text(group.name + " · " + fmtBytes(group.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            for (rel in group.paths) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(rel, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { asking = rel }) { Text(str(R.string.action_delete)) }
                                }
                            }
                        }
                    }
                }
                if (partial) Text(str(R.string.files_sizes_partial, FOLDER_SIZES_MAX), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}


/** Sends a test file to the console and reads it back, and says how fast each way went. */
@Composable
private fun SpeedTestDialog(s: FtpSettings, onClose: () -> Unit) {
    var result by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var problem by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            result = withContext(Dispatchers.IO) { ftpSpeedTest(s, cancelled = { stop }) }
        } catch (e: Exception) {
            problem = ftpFailureMessage(e)
        }
    }
    AlertDialog(
        onDismissRequest = { stop = true; onClose() },
        title = { Text(str(R.string.files_speed)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = result
                when {
                    problem.isNotEmpty() -> Text(problem, style = MaterialTheme.typography.bodySmall)
                    r == null -> {
                        Text(str(R.string.speed_running, fmtBytes(SPEED_TEST_BYTES)), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(str(R.string.speed_up, fmtBytes((mbPerSecond(SPEED_TEST_BYTES, r.first) * 1_048_576).toLong())), style = MaterialTheme.typography.titleSmall)
                        Text(str(R.string.speed_down, fmtBytes((mbPerSecond(SPEED_TEST_BYTES, r.second) * 1_048_576).toLong())), style = MaterialTheme.typography.titleSmall)
                    }
                }
                Text(str(R.string.speed_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { stop = true; onClose() }) { Text(str(if (result == null && problem.isEmpty()) R.string.action_cancel else R.string.action_close)) } }
    )
}
