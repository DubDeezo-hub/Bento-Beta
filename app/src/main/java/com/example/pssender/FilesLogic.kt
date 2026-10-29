package com.example.pssender // change to match your project's package name

import android.content.Context
import org.json.JSONArray

// ---------------------------------------------------------------------------------------------
// The Files tab without its screen: how a folder is arranged and searched, what is ticked, what a download of folders
// amounts to, deleting several things at once, and the folders that are pinned. The screen itself is in Files.kt.
// ---------------------------------------------------------------------------------------------

// ----- arranging a folder -----

/** How a folder is sorted. Folders always come first. */
enum class SortBy { NAME, SIZE, NEWEST }

/** The sort order called [text] (as kept in the settings); by name when it is missing or unknown. */
fun sortByOf(text: String?): SortBy = SortBy.values().firstOrNull { it.name == text } ?: SortBy.NAME

/** True when [name] fits [query]: every word of the query is somewhere in the name, whatever the case. An empty query fits everything. */
fun matchesQuery(name: String, query: String): Boolean {
    val words = query.trim().lowercase().split(' ', '\t').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val lower = name.lowercase()
    return words.all { lower.contains(it) }
}

/**
 * [entries] as they are shown: only the ones that fit [query], folders first, then in the order of [by] (the largest or
 * the newest first); entries that are equal in that are ordered by name.
 */
fun arrangeEntries(entries: List<FtpEntry>, by: SortBy, query: String): List<FtpEntry> {
    val byName = compareBy<FtpEntry> { it.name.lowercase() }.thenBy { it.name }
    val order = when (by) {
        SortBy.NAME -> compareBy<FtpEntry> { !it.isDir }.then(byName)
        SortBy.SIZE -> compareBy<FtpEntry> { !it.isDir }.thenByDescending { it.size }.then(byName)
        SortBy.NEWEST -> compareBy<FtpEntry> { !it.isDir }.thenByDescending { it.modified }.then(byName)
    }
    return entries.filter { matchesQuery(it.name, query) }.sortedWith(order)
}

/** The date [ms] (milliseconds since 1970) in the way of the phone's language, or empty when it isn't known (0). */
fun fmtDate(ms: Long): String = if (ms <= 0L) "" else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))

/** The small line under the name of an entry: its size (not for folders) and the day it last changed (when the listing has it). */
fun entryDetail(e: FtpEntry): String {
    val parts = ArrayList<String>()
    if (!e.isDir) parts.add(fmtBytes(e.size))
    if (e.modified > 0L) parts.add(fmtDate(e.modified))
    return joinList(parts)
}

// ----- ticking entries -----

/** [selected] with [name] ticked, or unticked when it was ticked. */
fun toggled(selected: Set<String>, name: String): Set<String> = if (name in selected) selected - name else selected + name

/** Ticks everything in [shown]; when all of it is ticked already, unticks it instead (what is not shown stays as it is). */
fun allOrNone(selected: Set<String>, shown: List<FtpEntry>): Set<String> {
    val names = shown.map { it.name }.toSet()
    return if (names.isNotEmpty() && selected.containsAll(names)) selected - names else selected + names
}

/** The ticked names that are still in [entries] (after the folder was loaded again). */
fun stillThere(selected: Set<String>, entries: List<FtpEntry>): Set<String> {
    val names = entries.map { it.name }.toSet()
    return selected.filter { it in names }.toSet()
}

/** The entries behind the ticked names, in the order of the folder. */
fun chosenEntries(selected: Set<String>, entries: List<FtpEntry>): List<FtpEntry> = entries.filter { it.name in selected }

// ----- downloading folders -----

/** The most files one download of folders may bring along. */
const val DOWNLOAD_MAX_FILES = 2000

/** The most folders that are looked into for one download (the console is asked about each of them). */
const val DOWNLOAD_MAX_FOLDERS = 1000

/** How deep folders inside folders are followed (the folder that was chosen is the first level). */
const val DOWNLOAD_MAX_DEPTH = 12

/**
 * One file to fetch: [remotePath] on the console, to be saved on the phone as [name] in the folder [saveIn] (below
 * Downloads/PS5, parts separated by "/"; empty for Downloads/PS5 itself). [size] is -1 when it isn't known.
 */
data class PlannedFile(val remotePath: String, val saveIn: String, val name: String, val size: Long)

/**
 * What a download comes to: the [files], how many links inside folders were [skippedLinks] (a link might lead anywhere,
 * even back to the folder it is in), and whether something was left out because there are too many files or folders, or
 * the folders go too deep ([truncated]).
 */
class DownloadPlan(val files: List<PlannedFile>, val skippedLinks: Int, val truncated: Boolean) {
    /** The size of everything whose size is known. */
    val totalBytes: Long get() = files.sumOf { it.size.coerceAtLeast(0L) }
}

/** [name] as a name of a file or folder on the phone: no slash, none of the characters that file systems refuse, never empty. */
fun safeLocalName(name: String): String {
    val cleaned = name.map { c -> if (c in "/\\:*?\"<>|" || c.isISOControl()) '_' else c }.joinToString("").trim().take(120).trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
}

/** The folders named by [saveIn] ("Games/Sub"), each one fit to be a folder name on the phone; "." and ".." are left out. */
fun saveInParts(saveIn: String): List<String> =
    saveIn.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }.map { safeLocalName(it) }

/**
 * The folder on the phone that a download goes into, the way MediaStore wants it: Download/PS5 (Download/PS4 for a PS4,
 * [root]) and below it the folders of [saveIn].
 */
fun downloadFolder(saveIn: String, root: String = "PS5"): String =
    "Download/" + safeLocalName(root) + saveInParts(saveIn).joinToString("") { "/$it" }

/** The file as the message "Saved to Downloads/PS5" names it: with the folders it was saved in ("Games/Sub/a.pkg"). */
fun savedAs(saveIn: String, name: String): String = (saveInParts(saveIn) + name).joinToString("/")

/**
 * What it takes to download the [chosen] entries of the folder [dir]. A file is taken as it is, a folder with everything
 * inside it; each file keeps the folders it lies in (starting with the folder that was chosen). [list] gives the entries
 * of a folder on the console; it may throw, and then so does this. At most [maxFiles] files are planned and at most
 * [maxFolders] folders looked into; when something is left out because of that, the plan says so.
 */
fun planDownload(
    dir: String,
    chosen: List<FtpEntry>,
    list: (String) -> List<FtpEntry>,
    maxFiles: Int = DOWNLOAD_MAX_FILES,
    maxFolders: Int = DOWNLOAD_MAX_FOLDERS
): DownloadPlan {
    val files = ArrayList<PlannedFile>()
    var links = 0
    var truncated = false
    var looked = 0

    fun walk(path: String, saveIn: String, depth: Int) {
        if (files.size >= maxFiles || looked >= maxFolders) {
            truncated = true
            return
        }
        looked++
        for (e in list(path)) {
            if (files.size >= maxFiles) {
                truncated = true
                return
            }
            when {
                e.isLink -> links++
                e.isDir ->
                    if (depth >= DOWNLOAD_MAX_DEPTH) truncated = true
                    else walk(joinPath(path, e.name), saveIn + "/" + safeLocalName(e.name), depth + 1)
                else -> files.add(PlannedFile(joinPath(path, e.name), saveIn, e.name, e.size))
            }
        }
    }

    for (e in chosen) {
        if (e.isDir) {
            walk(joinPath(dir, e.name), safeLocalName(e.name), 1)
        } else if (files.size >= maxFiles) {
            truncated = true
            break
        } else {
            files.add(PlannedFile(joinPath(dir, e.name), "", e.name, e.size))
        }
    }
    return DownloadPlan(files, links, truncated)
}

// ----- deleting several things -----

/** How deleting several entries went: [deleted] were removed, [failed] could not be (the message of each). */
class DeleteSummary(val deleted: Int, val failed: List<String>)

/**
 * Deletes each of [entries] with [delete] (which throws when it can't), and goes on after one that fails, so one
 * refusal does not leave the others behind.
 */
fun deleteEach(entries: List<FtpEntry>, delete: (FtpEntry) -> Unit): DeleteSummary {
    var deleted = 0
    val failed = ArrayList<String>()
    for (e in entries) {
        try {
            delete(e)
            deleted++
        } catch (x: Exception) {
            failed.add(x.message ?: x.javaClass.simpleName)
        }
    }
    return DeleteSummary(deleted, failed)
}

/** The sentence that says how deleting went. */
fun deleteSummaryText(s: DeleteSummary): String =
    if (s.failed.isEmpty()) {
        AppText.get(R.string.files_deleted_many, s.deleted)
    } else {
        AppText.get(R.string.files_delete_partial, s.deleted, s.failed.size, s.failed.first())
    }

// ----- pinned folders and the folder last viewed -----

/** The most folders that can be pinned for a console. */
const val PINS_MAX = 12

/** [path] without a trailing or doubled slash ("/" stays); empty when there is nothing in it. */
fun cleanFolder(path: String): String {
    val t = path.trim()
    if (t.isEmpty()) return ""
    val parts = t.split('/').filter { it.isNotEmpty() }
    return "/" + parts.joinToString("/")
}

/** [pins] with [path] added at the end (unchanged when it is pinned already, empty, or there is no more room). */
fun pinFolder(pins: List<String>, path: String): List<String> {
    val p = cleanFolder(path)
    return if (p.isEmpty() || p in pins || pins.size >= PINS_MAX) pins else pins + p
}

/** [pins] without [path]. */
fun unpinFolder(pins: List<String>, path: String): List<String> = pins.filter { it != cleanFolder(path) }

/** True when [path] is one of the [pins]. */
fun isPinned(pins: List<String>, path: String): Boolean = cleanFolder(path) in pins

/** The text on the button of each pinned folder: its own name, or the whole path when two would read the same. */
fun pinLabels(pins: List<String>): List<String> {
    val short = pins.map { if (it == "/") "/" else it.substringAfterLast('/') }
    return pins.mapIndexed { i, p -> if (short.count { it == short[i] } > 1) p else short[i] }
}

/** The pinned folders as text for the settings. */
fun pinsToJson(pins: List<String>): String = JSONArray(pins).toString()

/** The pinned folders in [text]; anything that is not a folder is left out, and there are never more than [PINS_MAX]. */
fun pinsFromJson(text: String?): List<String> {
    if (text.isNullOrBlank()) return emptyList()
    return try {
        val a = JSONArray(text)
        val found = ArrayList<String>()
        for (i in 0 until a.length()) {
            val p = cleanFolder(a.opt(i) as? String ?: "")
            if (p.isNotEmpty() && p !in found && found.size < PINS_MAX) found.add(p)
        }
        found
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * True when a folder that could not be opened is worth another try with the start folder: the console did answer, so the
 * folder is the problem (gone, or not allowed) and not the connection.
 */
fun folderIsTheProblem(e: Throwable): Boolean =
    when (topicFor(e)) {
        HelpTopic.UNREACHABLE, HelpTopic.PORT_CLOSED, HelpTopic.DROPPED -> false
        else -> true
    }

/** The folder the Files tab opens in: the one last viewed for this console, or else its start folder. */
fun startingFolder(last: String, start: String): String = last.trim().ifEmpty { start }

/** What the Files tab remembers for each console (by its id) and for the whole app. */
object FilesStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The folder last viewed for [consoleId]; empty when none. */
    fun lastFolder(ctx: Context, consoleId: String): String = prefs(ctx).getString("files_last_$consoleId", "") ?: ""

    fun saveLastFolder(ctx: Context, consoleId: String, path: String) {
        prefs(ctx).edit().putString("files_last_$consoleId", path).apply()
    }

    /** The pinned folders of [consoleId]. */
    fun pins(ctx: Context, consoleId: String): List<String> = pinsFromJson(prefs(ctx).getString("files_pins_$consoleId", null))

    fun savePins(ctx: Context, consoleId: String, pins: List<String>) {
        prefs(ctx).edit().putString("files_pins_$consoleId", pinsToJson(pins.take(PINS_MAX))).apply()
    }

    /** The pinned folders of each of the consoles [ids] that has some. */
    fun allPins(ctx: Context, ids: List<String>): Map<String, List<String>> =
        ids.associateWith { pins(ctx, it) }.filterValues { it.isNotEmpty() }

    fun sort(ctx: Context): SortBy = sortByOf(prefs(ctx).getString("files_sort", null))

    fun saveSort(ctx: Context, by: SortBy) {
        prefs(ctx).edit().putString("files_sort", by.name).apply()
    }
}

// ----- copying a phone folder to the console (folder sync, restoring a save) -----

/** A file inside a folder on the phone: its path inside that folder ("a/b.bin"), where to read it, its size (-1 unknown). */
data class PhoneFile(val rel: String, val uri: String, val size: Long)

/** A folder on the phone, read all the way down: its [files] and the [folders] inside it ("a", "a/b"). */
class PhoneTree(val name: String, val files: List<PhoneFile>, val folders: List<String>, val truncated: Boolean)

/**
 * What a sync comes to: the files to send ([copy]: [fresh] of them are not on the console yet, the rest differ in size),
 * how many are the same already ([same]), and the folders to make on the console first ([makeDirs]).
 */
class SyncPlan(val copy: List<PhoneFile>, val fresh: Int, val same: Int, val makeDirs: List<String>) {
    val changed: Int get() = copy.size - fresh
    val bytes: Long get() = copy.sumOf { it.size.coerceAtLeast(0L) }
}

/**
 * Compares a phone folder with the console's copy of it: a file is sent when the console has none by that path, or one
 * of another size (a size the phone does not know counts as different). With [everything], every file is sent.
 * Folders the console lacks are listed parents first.
 */
fun planSync(phone: PhoneTree, remoteFiles: Map<String, Long>, remoteFolders: Set<String>, everything: Boolean = false): SyncPlan {
    val copy = ArrayList<PhoneFile>()
    var fresh = 0
    var same = 0
    for (f in phone.files) {
        val there = remoteFiles[f.rel]
        when {
            there == null -> { copy.add(f); fresh++ }
            everything || f.size < 0L || there != f.size -> copy.add(f)
            else -> same++
        }
    }
    // Every folder a sent file lies in, and the empty ones too
    val need = LinkedHashSet<String>()
    fun addWithParents(dir: String) {
        val parts = dir.split('/').filter { it.isNotEmpty() }
        for (i in 1..parts.size) need.add(parts.take(i).joinToString("/"))
    }
    for (d in phone.folders) addWithParents(d)
    for (f in copy) if ('/' in f.rel) addWithParents(f.rel.substringBeforeLast('/'))
    val makeDirs = need.filter { it !in remoteFolders }.sortedBy { it.count { c -> c == '/' } }
    return SyncPlan(copy, fresh, same, makeDirs)
}

/** The folder a file of a sync goes to on the console: [base] plus the folders it lies in on the phone. */
fun syncTargetDir(base: String, rel: String): String =
    if ('/' in rel) joinPath(base.trimEnd('/').ifEmpty { "/" }, rel.substringBeforeLast('/')) else base.trimEnd('/').ifEmpty { "/" }

/** The name of a phone file inside a sync ("b.bin" for "a/b.bin"). */
fun syncFileName(rel: String): String = rel.substringAfterLast('/')
