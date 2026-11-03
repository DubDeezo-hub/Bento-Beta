package com.example.pssender

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Settings snapshots: the console's homebrew settings folder (etaHEN's on a PS5, GoldHEN's on a PS4) kept on the phone
// in one tap, compared with what is there now ("what changed"), and put back when an update or an experiment broke
// something. Only small files (settings, lists) are kept; big ones (payloads, cheats packs) are left out.
// ---------------------------------------------------------------------------------------------

/** What changed between a snapshot and the folder now: files that differ, were added since, or are gone. */
data class SnapshotDiff(val changed: List<String>, val added: List<String>, val removed: List<String>) {
    val same: Boolean get() = changed.isEmpty() && added.isEmpty() && removed.isEmpty()
}

/** Compares a snapshot's files with the folder's files now (relative path -> bytes). */
fun snapshotDiff(saved: Map<String, ByteArray>, now: Map<String, ByteArray>): SnapshotDiff = SnapshotDiff(
    changed = saved.keys.filter { it in now && !saved[it]!!.contentEquals(now[it]!!) }.sorted(),
    added = (now.keys - saved.keys).sorted(),
    removed = (saved.keys - now.keys).sorted()
)

/** Which files of a folder listing a snapshot keeps: small ones, not backups, up to the limits (in the listing's order). */
fun snapshotPick(files: Map<String, Long>): List<String> {
    var total = 0L
    val out = ArrayList<String>()
    for ((path, size) in files.entries.sortedBy { it.key }) {
        if (size < 0 || size > ConfigSnapshots.FILE_MAX || path.endsWith(".bak")) continue
        if (out.size >= ConfigSnapshots.FILES_MAX || total + size > ConfigSnapshots.TOTAL_MAX) break
        out.add(path)
        total += size
    }
    return out
}

object ConfigSnapshots {
    const val FILE_MAX = 256L * 1024
    const val TOTAL_MAX = 4L * 1024 * 1024
    const val FILES_MAX = 300
    const val KEEP = 10

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val human = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** The settings folder of a console type. */
    fun folderFor(type: ConsoleType): String = if (type == ConsoleType.PS4) "/data/GoldHEN" else "/data/etaHEN"

    private fun base(ctx: Context, profileId: String) = File(ctx.filesDir, "settings-snapshots/" + profileId.ifBlank { "console" })

    /** The snapshots of a console, newest first. */
    fun list(ctx: Context, profileId: String): List<File> =
        base(ctx, profileId).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    fun title(dir: File): String = try { human.format(stamp.parse(dir.name)!!) } catch (_: Exception) { dir.name }

    /** The files of a snapshot (relative path -> bytes). */
    fun read(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }

    /** The folder's small files on the console now (relative path -> bytes). Blocking. */
    fun current(s: FtpSettings, root: String): Map<String, ByteArray> {
        val tree = ftpTree(s, root, maxFiles = 2000)
        if (!tree.exists) return emptyMap()
        val pick = snapshotPick(tree.files)
        val out = HashMap<String, ByteArray>()
        ftpReadEach(s, pick.map { joinPath(root, it) }, FILE_MAX.toInt()) { path, data ->
            if (data != null) out[path.removePrefix(root).trimStart('/')] = data
        }
        return out
    }

    /** Takes a snapshot of [root] on the console. Gives how many files went in. Blocking. */
    fun take(ctx: Context, s: FtpSettings, profileId: String, root: String): Int {
        val files = current(s, root)
        if (files.isEmpty()) return 0
        val dir = File(base(ctx, profileId), stamp.format(Date()))
        for ((rel, data) in files) {
            val f = File(dir, rel)
            // Never outside the snapshot's folder, whatever a name on the console looks like
            if (!f.canonicalPath.startsWith(dir.canonicalPath)) continue
            f.parentFile?.mkdirs()
            f.writeBytes(data)
        }
        list(ctx, profileId).drop(KEEP).forEach { it.deleteRecursively() }
        return files.size
    }

    /** Puts a snapshot back on the console (its files replace the ones there; files added since stay). Blocking. */
    fun restore(dir: File, s: FtpSettings, root: String): Int = ftpWriteMany(s, read(dir).mapKeys { joinPath(root, it.key) })
}

@Composable
fun SnapshotsDialog(console: Profile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ftp = ActiveConsole.ftp
    val root = ConfigSnapshots.folderFor(console.type)
    var list by remember { mutableStateOf(ConfigSnapshots.list(ctx, console.id)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf<Pair<String, SnapshotDiff>?>(null) }
    var restoring by remember { mutableStateOf<File?>(null) }

    fun work(job: suspend () -> String) {
        busy = true
        scope.launch {
            status = try { job() } catch (e: Exception) { failedText(e) }
            busy = false
            list = ConfigSnapshots.list(ctx, console.id)
        }
    }

    diff?.let { (title, d) ->
        AlertDialog(
            onDismissRequest = { diff = null },
            title = { Text(str(R.string.snap_diff_title, title)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (d.same) Text(str(R.string.snap_same))
                    d.changed.forEach { Text(str(R.string.snap_changed, it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    d.added.forEach { Text(str(R.string.snap_added, it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    d.removed.forEach { Text(str(R.string.snap_removed, it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { diff = null }) { Text(str(R.string.action_close)) } }
        )
        return
    }
    restoring?.let { dir ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(str(R.string.snap_restore_title)) },
            text = { Text(str(R.string.snap_restore_text, ConfigSnapshots.title(dir), root, console.type.model)) },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    val s = ftp ?: return@TextButton
                    work { AppText.get(R.string.snap_restored, withContext(Dispatchers.IO) { ConfigSnapshots.restore(dir, s, root) }) }
                }) { Text(str(R.string.snap_restore)) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(str(R.string.action_cancel)) } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.snap_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(str(R.string.snap_intro, root, console.type.model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ftp == null) Text(str(R.string.snap_need_ftp, console.type.model), style = MaterialTheme.typography.bodySmall)
                Button(enabled = ftp != null && !busy, onClick = {
                    val s = ftp ?: return@Button
                    work {
                        val n = withContext(Dispatchers.IO) { ConfigSnapshots.take(ctx, s, console.id, root) }
                        if (n == 0) AppText.get(R.string.snap_nothing, root) else AppText.get(R.string.snap_taken, n)
                    }
                }) { Text(str(R.string.snap_take)) }
                if (busy) Text(str(R.string.snap_working), style = MaterialTheme.typography.bodySmall)
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                if (list.isEmpty()) Text(str(R.string.snap_none), style = MaterialTheme.typography.bodySmall)
                list.forEach { dir ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ConfigSnapshots.title(dir), modifier = Modifier.weight(1f))
                        TextButton(enabled = ftp != null && !busy, onClick = {
                            val s = ftp ?: return@TextButton
                            work {
                                val d = withContext(Dispatchers.IO) { snapshotDiff(ConfigSnapshots.read(dir), ConfigSnapshots.current(s, root)) }
                                diff = ConfigSnapshots.title(dir) to d
                                ""
                            }
                        }) { Text(str(R.string.snap_compare)) }
                        TextButton(enabled = ftp != null && !busy, onClick = { restoring = dir }) { Text(str(R.string.snap_restore)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}
