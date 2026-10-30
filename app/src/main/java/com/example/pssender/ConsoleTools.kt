package com.example.pssender // change to match your project's package name

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Console tools on the Files tab: the installed games and apps, the saves (back up and put back), and the screenshots
// and videos shortcut. Also the package check of the Install tab and the folder sync of the Upload tab.
// ---------------------------------------------------------------------------------------------

/** What the Files tab shows. */
enum class FilesView { BROWSE, APPS, SAVES }

/** Where the console keeps things. */
object ConsolePaths {
    const val APPMETA = "/user/appmeta"
    const val APPS = "/user/app"
    const val PATCHES = "/user/patch"
    const val HOME = "/user/home"
    const val MEDIA = "/user/av_contents"
    const val SAVES_PS5 = "savedata_prospero"
    const val SAVES_PS4 = "savedata"

    fun isPs5Title(id: String) = id.startsWith("PPSA") || id.startsWith("PPSB")
}

/** Turns icon bytes into a picture, or null when they are not one. */
fun iconOf(bytes: ByteArray?): ImageBitmap? =
    try {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    } catch (_: Throwable) {
        null
    }

// ---------------------------------------------------------------------------------------------
// Installed games and apps
// ---------------------------------------------------------------------------------------------

data class AppEntry(val id: String, val info: TitleInfo? = null, val icon: ImageBitmap? = null, val hasPatch: Boolean = false) {
    val shownName: String get() = info?.name?.ifBlank { null } ?: id
}

object ConsoleApps {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    /** The console the list belongs to ("host:port"). */
    private var loadedFor = ""

    fun nameOf(id: String): String? = apps.firstOrNull { it.id == id }?.info?.name?.ifBlank { null }
    fun iconOf(id: String): ImageBitmap? = apps.firstOrNull { it.id == id }?.icon

    fun forget() {
        job?.cancel()
        apps = emptyList()
        loading = false
        message = ""
        loadedFor = ""
    }

    /** Loads the list (again with [force]); names and icons fill in as they arrive. */
    fun load(ctx: Context, s: FtpSettings, force: Boolean = false) {
        val key = "${s.host}:${s.port}"
        if (loading && key == loadedFor) return
        if (!force && key == loadedFor && apps.isNotEmpty()) return
        job?.cancel()
        loadedFor = key
        loading = true
        message = AppText.get(R.string.apps_loading)
        val cacheDir = File(ctx.cacheDir, "appicons").apply { mkdirs() }
        val lang = Locale.getDefault().toLanguageTag()
        job = scope.launch {
            try {
                val lists = withContext(Dispatchers.IO) { ftpListMany(s, listOf(ConsolePaths.APPMETA, ConsolePaths.PATCHES)) }
                val patched = lists[ConsolePaths.PATCHES].orEmpty().filter { it.isDir }.map { it.name }.toSet()
                val ids = lists[ConsolePaths.APPMETA].orEmpty().filter { it.isDir && TitleMeta.isTitleId(it.name) }.map { it.name }.sorted()
                if (key != loadedFor) return@launch
                apps = ids.map { id ->
                    val cached = File(cacheDir, "$id.png").takeIf { it.isFile }?.readBytes()
                    AppEntry(id, null, iconOf(cached), id in patched)
                }
                if (ids.isEmpty()) {
                    message = AppText.get(R.string.apps_none, ConsolePaths.APPMETA)
                    return@launch
                }
                message = ""
                // The details: a PS5 game has a param.json, a PS4 game a param.sfo
                val paths = ids.map { id ->
                    val dir = joinPath(ConsolePaths.APPMETA, id)
                    if (ConsolePaths.isPs5Title(id)) joinPath(dir, "param.json") else joinPath(dir, "param.sfo")
                }
                val found = HashMap<String, TitleInfo>()
                val missing = ArrayList<String>()
                withContext(Dispatchers.IO) {
                    ftpReadEach(s, paths, 512 * 1024, stop = { key != loadedFor }) { path, data ->
                        val id = path.removePrefix(ConsolePaths.APPMETA + "/").substringBefore('/')
                        val info = data?.let { parseParam(path, it, lang) }
                        if (info != null) found[id] = info else missing.add(id)
                        if (info != null && found.size % 12 == 0) publish(found, key)
                    }
                    // The other kind of file for the ones that had none (a PS4 game on a PS5, for example)
                    if (missing.isNotEmpty()) {
                        val other = missing.map { id ->
                            val dir = joinPath(ConsolePaths.APPMETA, id)
                            if (ConsolePaths.isPs5Title(id)) joinPath(dir, "param.sfo") else joinPath(dir, "param.json")
                        }
                        ftpReadEach(s, other, 512 * 1024, stop = { key != loadedFor }) { path, data ->
                            val id = path.removePrefix(ConsolePaths.APPMETA + "/").substringBefore('/')
                            data?.let { parseParam(path, it, lang) }?.let { found[id] = it }
                        }
                    }
                }
                publish(found, key)
                // Icons that aren't kept on the phone yet
                val noIcon = apps.filter { it.icon == null }.map { joinPath(joinPath(ConsolePaths.APPMETA, it.id), "icon0.png") }
                if (noIcon.isNotEmpty()) {
                    val icons = HashMap<String, ImageBitmap>()
                    withContext(Dispatchers.IO) {
                        ftpReadEach(s, noIcon, 2 * 1024 * 1024, stop = { key != loadedFor }) { path, data ->
                            val id = path.removePrefix(ConsolePaths.APPMETA + "/").substringBefore('/')
                            val pic = iconOf(data)
                            if (pic != null && data != null) {
                                icons[id] = pic
                                try { File(cacheDir, "$id.png").writeBytes(data) } catch (_: Exception) {}
                            }
                        }
                    }
                    if (key == loadedFor) apps = apps.map { a -> icons[a.id]?.let { a.copy(icon = it) } ?: a }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (key == loadedFor) message = ftpFailureMessage(e)
            } finally {
                if (key == loadedFor) loading = false
            }
        }
    }

    /** Shows the details found so far (from any thread). */
    private fun publish(found: Map<String, TitleInfo>, key: String) {
        val copy = HashMap(found)
        scope.launch(Dispatchers.Main) {
            if (key == loadedFor) {
                apps = apps.map { a -> copy[a.id]?.let { a.copy(info = it) } ?: a }
                    .sortedWith(compareBy({ it.info == null }, { it.shownName.lowercase() }))
            }
        }
    }

    private fun parseParam(path: String, data: ByteArray, lang: String): TitleInfo? =
        try {
            if (path.endsWith(".json")) TitleMeta.fromParamJson(String(data, Charsets.UTF_8), lang)
            else TitleMeta.fromSfo(Sfo.parse(data))
        } catch (_: Exception) {
            null
        }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, size: Int = 48) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)).background(cs.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        else Text("🎮", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TitleRow(icon: ImageBitmap?, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "PPSA01234 · v01.000.000 · update installed" */
@Composable
private fun appDetail(a: AppEntry): String {
    val parts = ArrayList<String>()
    parts.add(a.id)
    a.info?.version?.takeIf { it.isNotBlank() }?.let { parts.add("v$it") }
    if (a.hasPatch) parts.add(str(R.string.apps_patched))
    return parts.joinToString(" · ")
}

@Composable
fun AppsView(settings: FtpSettings?, onOpenFolder: (String) -> Unit, onSaves: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = ConsoleApps
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<AppEntry?>(null) }
    LaunchedEffect(settings?.host, settings?.port) {
        if (settings != null) c.load(ctx, settings)
    }
    val shown = remember(c.apps, query) {
        val q = query.trim()
        if (q.isEmpty()) c.apps else c.apps.filter { it.shownName.contains(q, true) || it.id.contains(q, true) }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TipTitle(str(R.string.apps_title, c.apps.size), Tip.APPS, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = settings != null && !c.loading, onClick = { if (settings != null) c.load(ctx, settings, force = true) }) {
                Text(str(R.string.files_refresh))
            }
        }
        if (c.apps.size > 6) Field(str(R.string.files_search_hint), query) { query = it }
        if (c.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (settings == null) Text(str(R.string.files_need_ip), style = MaterialTheme.typography.bodySmall)
        else if (c.message.isNotEmpty()) {
            Text(c.message, style = MaterialTheme.typography.bodySmall)
            HelpLink(c.message)
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(shown) { a -> TitleRow(a.icon, a.shownName, appDetail(a)) { open = a } }
        }
    }
    open?.let { a ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(a.icon, 56)
                    Text(a.shownName, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetaLines(a.info, a.id)
                    if (a.hasPatch) Text(str(R.string.apps_patched), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { open = null; onOpenFolder(joinPath(ConsolePaths.APPS, a.id)) }) { Text(str(R.string.apps_open_folder)) }
                    OutlinedButton(onClick = { open = null; onSaves(a.id) }) { Text(str(R.string.apps_its_saves)) }
                    TextButton(onClick = { copyText(ctx, "title id", a.id, AppText.get(R.string.help_copied)) }) { Text(str(R.string.apps_copy_id)) }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text(str(R.string.action_close)) } }
        )
    }
}

/** Title ID, version, kind, content ID and the firmware a title needs, each on its own line when known. */
@Composable
private fun MetaLines(info: TitleInfo?, id: String = "") {
    val small = MaterialTheme.typography.bodySmall
    Text(str(R.string.meta_title_id, info?.titleId?.ifBlank { null } ?: id.ifBlank { "?" }), style = small)
    info?.version?.takeIf { it.isNotBlank() }?.let { Text(str(R.string.meta_version, it), style = small) }
    info?.kindRes?.takeIf { it != 0 }?.let { Text(str(R.string.meta_kind, str(it)), style = small) }
    info?.needsFirmware?.takeIf { it.isNotBlank() }?.let { Text(str(R.string.meta_needs_fw, it), style = small) }
    info?.contentId?.takeIf { it.isNotBlank() }?.let { Text(str(R.string.meta_content_id, it), style = small, maxLines = 2) }
}

// ---------------------------------------------------------------------------------------------
// Saves
// ---------------------------------------------------------------------------------------------

/** One game's saves: the folder [path] holds them. [ps5] tells a PS5 save from a PS4 one. */
data class SaveEntry(val id: String, val path: String, val ps5: Boolean)

object ConsoleSaves {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var users by mutableStateOf<List<String>>(emptyList())
        private set
    var user by mutableStateOf("")
        private set
    var saves by mutableStateOf<List<SaveEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    /** Shows only one game (from the games list); empty for all. */
    var only by mutableStateOf("")
    private var loadedFor = ""

    fun forget() {
        users = emptyList(); user = ""; saves = emptyList(); loading = false; message = ""; loadedFor = ""
    }

    fun load(s: FtpSettings, force: Boolean = false) {
        val key = "${s.host}:${s.port}"
        if (loading) return
        if (!force && key == loadedFor && users.isNotEmpty()) return
        loadedFor = key
        loading = true
        message = AppText.get(R.string.files_loading)
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    ftpListOrEmpty(s, ConsolePaths.HOME).filter { it.isDir && it.name.matches(Regex("[0-9a-fA-F]{8}")) }.map { it.name }
                }
                users = found
                if (found.isEmpty()) {
                    message = AppText.get(R.string.saves_no_users, ConsolePaths.HOME)
                    saves = emptyList()
                } else {
                    message = ""
                    val pick = if (user in found) user else found.first()
                    loadUser(s, pick)
                }
            } catch (e: Exception) {
                message = ftpFailureMessage(e)
            } finally {
                loading = false
            }
        }
    }

    private suspend fun loadUser(s: FtpSettings, u: String) {
        user = u
        val base = joinPath(ConsolePaths.HOME, u)
        val ps5 = joinPath(base, ConsolePaths.SAVES_PS5)
        val ps4 = joinPath(base, ConsolePaths.SAVES_PS4)
        val lists = withContext(Dispatchers.IO) { ftpListMany(s, listOf(ps5, ps4)) }
        saves = lists[ps5].orEmpty().filter { it.isDir }.map { SaveEntry(it.name, joinPath(ps5, it.name), true) } +
            lists[ps4].orEmpty().filter { it.isDir }.map { SaveEntry(it.name, joinPath(ps4, it.name), false) }
        if (saves.isEmpty()) message = AppText.get(R.string.saves_none)
    }

    fun pickUser(s: FtpSettings, u: String) {
        if (loading) return
        loading = true
        message = ""
        scope.launch {
            try { loadUser(s, u) } catch (e: Exception) { message = ftpFailureMessage(e) } finally { loading = false }
        }
    }
}

/** Something waiting for the person to say yes: putting a backup back into a game's save folder. */
private class RestoreAsk(val save: SaveEntry, val tree: Uri, val phone: PhoneTree)

@Composable
fun SavesView(
    settings: FtpSettings?,
    onOpenFolder: (String) -> Unit,
    onStartMany: (List<TransferRequest>) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ConsoleSaves
    var open by remember { mutableStateOf<SaveEntry?>(null) }
    var restoreFor by remember { mutableStateOf<SaveEntry?>(null) }
    var ask by remember { mutableStateOf<RestoreAsk?>(null) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(settings?.host, settings?.port) {
        if (settings != null) {
            c.load(settings)
            ConsoleApps.load(ctx, settings) // for the games' names and icons
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val save = restoreFor
        restoreFor = null
        if (tree != null && save != null) {
            keepTreeAccess(ctx.contentResolver, tree)
            working = true
            c.message = AppText.get(R.string.saves_reading_backup)
            scope.launch {
                try {
                    val phone = withContext(Dispatchers.IO) { walkPhoneTree(ctx.contentResolver, tree) }
                    c.message = ""
                    if (phone.files.isEmpty()) c.message = AppText.get(R.string.saves_backup_empty) else ask = RestoreAsk(save, tree, phone)
                } catch (e: Exception) {
                    c.message = failedText(e)
                } finally {
                    working = false
                }
            }
        }
    }

    val shown = c.saves.filter { c.only.isEmpty() || it.id == c.only }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TipTitle(str(R.string.saves_title), Tip.SAVES, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = settings != null && !c.loading, onClick = { if (settings != null) c.load(settings, force = true) }) {
                Text(str(R.string.files_refresh))
            }
        }
        if (c.users.size > 1) {
            ChipRow {
                c.users.forEachIndexed { i, u ->
                    Chip(str(R.string.saves_user, i + 1), u == c.user, enabled = !c.loading) { if (settings != null) c.pickUser(settings, u) }
                }
            }
        }
        if (c.only.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    str(R.string.saves_only, ConsoleApps.nameOf(c.only) ?: c.only),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { c.only = "" }) { Text(str(R.string.saves_show_all)) }
            }
        }
        if (c.loading || working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (settings == null) Text(str(R.string.files_need_ip), style = MaterialTheme.typography.bodySmall)
        else if (c.message.isNotEmpty()) {
            Text(c.message, style = MaterialTheme.typography.bodySmall)
            HelpLink(c.message)
        }
        if (!c.loading && c.only.isNotEmpty() && shown.isEmpty() && c.users.isNotEmpty()) {
            Text(str(R.string.saves_none_for_game), style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(shown) { sv ->
                TitleRow(
                    ConsoleApps.iconOf(sv.id),
                    ConsoleApps.nameOf(sv.id) ?: sv.id,
                    sv.id + " · " + str(if (sv.ps5) R.string.saves_kind_ps5 else R.string.saves_kind_ps4)
                ) { open = sv }
            }
        }
    }

    open?.let { sv ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(ConsoleApps.nameOf(sv.id) ?: sv.id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sv.path, style = MaterialTheme.typography.bodySmall)
                    Text(str(R.string.saves_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = settings != null && !working, onClick = {
                        open = null
                        if (settings != null) backUp(scope, settings, sv, onStartMany) { working = it }
                    }) { Text(str(R.string.saves_backup)) }
                    OutlinedButton(enabled = settings != null && !working, onClick = {
                        open = null
                        restoreFor = sv
                        picker.launch(null)
                    }) { Text(str(R.string.saves_restore)) }
                    TextButton(onClick = { open = null; onOpenFolder(sv.path) }) { Text(str(R.string.apps_open_folder)) }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text(str(R.string.action_close)) } }
        )
    }

    ask?.let { a ->
        val name = ConsoleApps.nameOf(a.save.id) ?: a.save.id
        val looksOther = a.phone.name.isNotEmpty() && !a.phone.name.startsWith(a.save.id)
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(str(R.string.saves_restore_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(str(R.string.saves_restore_text, a.phone.files.size, a.phone.name.ifEmpty { "?" }, name))
                    if (looksOther) Text(str(R.string.saves_restore_other, a.save.id), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text(str(R.string.saves_restore_warn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    if (settings != null) {
                        working = true
                        c.message = AppText.get(R.string.saves_restoring)
                        scope.launch {
                            try {
                                val plan = planSync(a.phone, emptyMap(), emptySet(), everything = true)
                                withContext(Dispatchers.IO) { ftpMakeDirs(settings, a.save.path, plan.makeDirs) }
                                onStartMany(uploadRequests(settings, a.save.path, plan.copy))
                                c.message = AppText.get(R.string.saves_restore_queued, plan.copy.size)
                            } catch (e: Exception) {
                                c.message = ftpFailureMessage(e)
                            } finally {
                                working = false
                            }
                        }
                    }
                }) { Text(str(R.string.saves_restore_go)) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(str(R.string.action_cancel)) } }
        )
    }
}

/** Downloads a game's save folder to Downloads/PS5/Saves/<title ID> <date and time>. */
private fun backUp(scope: CoroutineScope, s: FtpSettings, sv: SaveEntry, onStartMany: (List<TransferRequest>) -> Unit, busy: (Boolean) -> Unit) {
    busy(true)
    ConsoleSaves.message = AppText.get(R.string.files_dl_planning)
    scope.launch {
        try {
            val plan = withContext(Dispatchers.IO) {
                ftpPlanDownload(s, parentPath(sv.path), listOf(FtpEntry(sv.id, isDir = true, isLink = false, size = 0L)))
            }
            if (plan.files.isEmpty()) {
                ConsoleSaves.message = AppText.get(R.string.saves_empty_folder)
            } else {
                val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
                val folder = "Saves/" + safeLocalName("${sv.id} $stamp")
                onStartMany(plan.files.map { f ->
                    // The plan saves into "<title ID>/…": the backup goes into its own dated folder instead
                    val inner = f.saveIn.substringAfter('/', "")
                    TransferRequest(
                        name = f.name,
                        host = s.host, port = s.port, user = s.user, pass = s.pass,
                        dir = if (inner.isEmpty()) folder else "$folder/$inner",
                        remotePath = f.remotePath,
                        remoteSize = f.size,
                        model = s.model
                    )
                })
                ConsoleSaves.message = AppText.get(R.string.saves_backup_queued, plan.files.size, folder, s.model)
            }
        } catch (e: Exception) {
            ConsoleSaves.message = ftpFailureMessage(e)
        } finally {
            busy(false)
        }
    }
}

/** Uploads of phone files into [base] on the console, each into the folder it lies in on the phone. */
fun uploadRequests(s: FtpSettings, base: String, files: List<PhoneFile>): List<TransferRequest> =
    files.map { f ->
        TransferRequest(
            name = syncFileName(f.rel),
            host = s.host, port = s.port, user = s.user, pass = s.pass,
            dir = syncTargetDir(base, f.rel),
            fileUri = f.uri,
            model = s.model
        )
    }

// ---------------------------------------------------------------------------------------------
// Package check (Install tab)
// ---------------------------------------------------------------------------------------------

/** One package being looked at: where it is and, once read, what it says (or why it could not be read). */
class PkgLook(val label: String, val info: PkgInfo? = null, val icon: ImageBitmap? = null, val problem: String = "", val done: Boolean = false)

/** Reads what each of [links] (web links) or [file] (a package on the phone) says about itself, and shows it. */
@Composable
fun PkgInfoDialog(links: List<String>, file: Uri?, ftp: FtpSettings? = null, type: ConsoleType = ActiveConsole.type, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // What the console has, to say whether each package is new, an update or already there (over FTP)
    LaunchedEffect(ftp) { if (ftp != null) ConsoleApps.load(ctx, ftp) }
    val firmware = remember { ConsoleFirmware.get(ctx, ActiveConsole.id) }
    var looks by remember(links, file) {
        mutableStateOf(
            if (file != null) listOf(PkgLook(ctx.contentResolver.displayName(file)))
            else links.map { PkgLook(linkLabel(it)) }
        )
    }
    LaunchedEffect(links, file) {
        val sources: List<() -> RangeReader> =
            if (file != null) listOf({ UriRangeReader(ctx.contentResolver, file) }) else links.map { l -> { HttpRangeReader(l) } }
        sources.forEachIndexed { i, make ->
            val result = withContext(Dispatchers.IO) {
                try {
                    val info = Pkg.read(make())
                    PkgLook(looks[i].label, info, iconOf(info.icon), done = true)
                } catch (e: Exception) {
                    PkgLook(looks[i].label, problem = e.message ?: e.javaClass.simpleName, done = true)
                }
            }
            looks = looks.toMutableList().also { it[i] = result }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { TipTitle(str(R.string.meta_title), Tip.PKG_INFO) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                looks.forEach { look ->
                    val info = look.info
                    val compare = when {
                        ftp == null || info == null || info.kind == PkgInfo.Kind.UNKNOWN -> null
                        ConsoleApps.loading && ConsoleApps.apps.isEmpty() -> listOf(PkgNote(PkgNoteLevel.INFO, R.string.meta_installed_checking))
                        ConsoleApps.apps.isEmpty() -> listOf(PkgNote(PkgNoteLevel.INFO, R.string.meta_installed_cant_see))
                        else -> {
                            val id = info.title?.titleId?.ifBlank { null } ?: PkgCheck.titleIdOf(info.title?.contentId?.ifBlank { null } ?: info.contentId)
                            PkgCheck.notes(
                                titleId = id,
                                version = info.title?.version.orEmpty(),
                                category = info.title?.category.orEmpty(),
                                installed = ConsoleApps.apps.firstOrNull { it.id == id },
                                pkgIsPs5 = info.kind == PkgInfo.Kind.PS5,
                                consoleIsPs5 = type == ConsoleType.PS5,
                                needsFirmware = info.title?.needsFirmware.orEmpty(),
                                consoleFirmware = firmware
                            )
                        }
                    }
                    PkgLookCard(look, compare.orEmpty())
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

@Composable
private fun PkgLookCard(look: PkgLook, notes: List<PkgNote> = emptyList()) {
    val small = MaterialTheme.typography.bodySmall
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        AppIcon(look.icon, 56)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val info = look.info
            val name = info?.title?.name?.ifBlank { null }
            Text(name ?: look.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            when {
                !look.done -> Text(str(R.string.meta_reading), style = small)
                look.problem.isNotEmpty() -> Text(str(R.string.meta_failed, look.problem), style = small, color = MaterialTheme.colorScheme.error)
                info != null -> {
                    Text(
                        str(
                            when (info.kind) {
                                PkgInfo.Kind.PS4 -> R.string.meta_pkg_ps4
                                PkgInfo.Kind.PS5 -> R.string.meta_pkg_ps5
                                PkgInfo.Kind.UNKNOWN -> R.string.meta_pkg_unknown
                            }
                        ) + if (info.size > 0) " · " + fmtBytes(info.size) else "",
                        style = small
                    )
                    if (info.title != null) MetaLines(info.title)
                    else if (info.contentId.isNotBlank()) Text(str(R.string.meta_content_id, info.contentId), style = small)
                    if (info.kind == PkgInfo.Kind.PS5) Text(str(R.string.meta_ps5_note), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val colors = statusColors()
                    notes.forEach { n ->
                        Text(
                            str(n.res, *n.args.toTypedArray()),
                            style = small,
                            fontWeight = if (n.level == PkgNoteLevel.INFO) null else FontWeight.Medium,
                            color = when (n.level) {
                                PkgNoteLevel.GOOD -> colors.good
                                PkgNoteLevel.WARN -> colors.warn
                                PkgNoteLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Folder sync (Upload tab)
// ---------------------------------------------------------------------------------------------

/** A phone folder kept in step with a folder on the console. */
data class SyncPair(val tree: String, val name: String, val remote: String)

object SyncStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun key(profileId: String) = "sync_pairs_$profileId"
    const val MAX = 6

    fun load(ctx: Context, profileId: String): List<SyncPair> =
        (prefs(ctx).getString(key(profileId), "") ?: "").split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 3 && p[0].isNotEmpty()) SyncPair(p[0], p[1], p[2]) else null
        }

    fun save(ctx: Context, profileId: String, pairs: List<SyncPair>) {
        val text = pairs.take(MAX).joinToString("\n") { listOf(it.tree, it.name, it.remote).joinToString("\t") { s -> s.replace('\t', ' ').replace('\n', ' ') } }
        prefs(ctx).edit().putString(key(profileId), text).apply()
    }
}

/** What a sync came to before it is sent, waiting for a yes. */
private class SyncAsk(val pair: SyncPair, val plan: SyncPlan, val truncated: Boolean)

@Composable
fun FolderSyncSection(settings: FtpSettings?, profile: Profile, onStartMany: (List<TransferRequest>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairs by remember(profile.id) { mutableStateOf(SyncStore.load(ctx, profile.id)) }
    var adding by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<SyncAsk?>(null) }
    var removeFor by remember { mutableStateOf<SyncPair?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            keepTreeAccess(ctx.contentResolver, tree)
            val name = try {
                android.provider.DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
            } catch (_: Exception) {
                ""
            }.ifEmpty { "Folder" }
            adding = tree to name
        }
    }

    val runSync: (SyncPair) -> Unit = { pair ->
        if (settings != null && !busy) {
            busy = true
            status = AppText.get(R.string.sync_comparing)
            scope.launch {
                try {
                    val (phone, remote) = withContext(Dispatchers.IO) {
                        walkPhoneTree(ctx.contentResolver, Uri.parse(pair.tree)) to ftpTree(settings, pair.remote)
                    }
                    val plan = planSync(phone, remote.files, remote.folders)
                    status = ""
                    if (plan.copy.isEmpty() && plan.makeDirs.isEmpty()) {
                        status = AppText.get(R.string.sync_nothing, plan.same)
                    } else {
                        ask = SyncAsk(pair, plan, phone.truncated || remote.truncated)
                    }
                } catch (e: SecurityException) {
                    status = AppText.get(R.string.sync_lost_access)
                } catch (e: Exception) {
                    status = ftpFailureMessage(e)
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TipTitle(str(R.string.sync_title), Tip.SYNC)
        Text(str(R.string.sync_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        pairs.forEach { p ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("→ " + p.remote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(enabled = settings != null && !busy, onClick = { runSync(p) }) { Text(str(R.string.sync_now)) }
                TextButton(onClick = { removeFor = p }) { Text("✕") }
            }
        }
        OutlinedButton(enabled = pairs.size < SyncStore.MAX, onClick = { picker.launch(null) }) { Text(str(R.string.sync_add)) }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
            HelpLink(status)
        }
    }

    adding?.let { (tree, name) ->
        var remote by remember(tree) { mutableStateOf(joinPath(profile.ftpDir.trim().ifEmpty { "/data" }, name)) }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(str(R.string.sync_add_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(str(R.string.sync_add_text), style = MaterialTheme.typography.bodySmall)
                    Field(str(R.string.sync_remote), remote) { remote = it }
                }
            },
            confirmButton = {
                TextButton(enabled = remote.trim().startsWith("/"), onClick = {
                    val p = SyncPair(tree.toString(), name, remote.trim().trimEnd('/').ifEmpty { "/" })
                    pairs = (pairs.filter { it.tree != p.tree || it.remote != p.remote } + p).takeLast(SyncStore.MAX)
                    SyncStore.save(ctx, profile.id, pairs)
                    adding = null
                    runSync(p)
                }) { Text(str(R.string.sync_now)) }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(str(R.string.sync_confirm_title, a.pair.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(str(R.string.sync_confirm_text, a.plan.fresh, a.plan.changed, a.plan.same, fmtBytes(a.plan.bytes)))
                    if (a.plan.makeDirs.isNotEmpty()) Text(str(R.string.sync_confirm_dirs, a.plan.makeDirs.size), style = MaterialTheme.typography.bodySmall)
                    if (a.truncated) Text(str(R.string.sync_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    if (settings != null) {
                        busy = true
                        status = AppText.get(R.string.sync_making_dirs)
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { ftpMakeDirs(settings, a.pair.remote, a.plan.makeDirs, makeTop = true) }
                                onStartMany(uploadRequests(settings, a.pair.remote, a.plan.copy))
                                status = AppText.get(R.string.sync_queued, a.plan.copy.size)
                            } catch (e: Exception) {
                                status = ftpFailureMessage(e)
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text(str(R.string.sync_send)) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    removeFor?.let { p ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text(str(R.string.sync_remove_title, p.name)) },
            text = { Text(str(R.string.sync_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pairs = pairs.filter { it != p }
                    SyncStore.save(ctx, profile.id, pairs)
                    removeFor = null
                }) { Text(str(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text(str(R.string.action_cancel)) } }
        )
    }
}
