package com.example.pssender // change to match your project's package name

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
    var path by mutableStateOf("")
    var entries by mutableStateOf<List<FtpEntry>>(emptyList())
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")

    /** Bumped by [reset]; the results of jobs that started before it are thrown away. */
    private var generation = 0

    /** Forgets everything (used when another console is selected). */
    fun reset() {
        generation++
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
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onHold).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (picking) Checkbox(checked = ticked, onCheckedChange = { onTap() })
        Text(if (e.isDir) "📁" else if (e.isLink) "🔗" else "📄")
        Column(modifier = Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = entryDetail(e)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
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
    BackHandler(enabled = settings != null && model.path.isNotEmpty() && model.path != "/" && !model.busy) {
        if (settings != null) model.open(scope, settings, parentPath(model.path))
    }
    BackHandler(enabled = picking) { selected = emptySet() }

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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }
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
            }
            if (pins.isNotEmpty()) {
                val labels = pinLabels(pins)
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
