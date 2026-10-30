package com.example.pssender

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------------------------
// Backup and restore: everything that took time to set up, in one file that can be kept anywhere.
// FTP passwords are never saved by the app, so they are not in it either.
// ---------------------------------------------------------------------------------------------

/** The newest backup format this version understands. */
const val BACKUP_FORMAT = 1

/** Together, the payloads in a backup stay below this size, so building the file can't fill the phone's memory. */
const val BACKUP_SHELF_MAX_BYTES = 16L * 1024 * 1024

/** The biggest backup file that is read. A backup is mostly text and its payloads are small; a bigger file is something else. */
const val BACKUP_READ_MAX_BYTES = 32 * 1024 * 1024

/** A payload from the shelf, kept in a backup. */
class ShelfFile(val name: String, val bytes: ByteArray)

/**
 * Everything a backup holds. A part that is null is not in the file; restoring leaves that part as it is on the phone
 * (so a file that was cut down by hand can't wipe the bookmarks, for example).
 */
class BackupData(
    val consoles: List<Profile>,
    val activeId: String,
    val home: String,
    val bookmarks: List<Bookmark>?,
    val adblockOn: Boolean?,
    val adblockCustom: String?,
    val shelf: List<ShelfFile>,
    /** The payload chains (see Chains.kt); null when the file has none, so restoring leaves the ones on the phone alone. */
    val chains: List<Chain>? = null,
    /** The folders pinned in the Files tab, by console id; null when the file has none, so restoring leaves the ones on the phone alone. */
    val pins: Map<String, List<String>>? = null,
    /** When the backup was made ("2026-10-11T12:34:56Z"), as written in the file. */
    val savedAt: String = "",
    /** Payloads that were left out because the backup would have become too large. */
    val skipped: Int = 0
)

/** Why a file could not be used as a backup. */
enum class BackupProblem { NOT_A_BACKUP, TOO_NEW }

/** What reading a backup file gave: the [data], or the [problem]. */
class BackupRead(val data: BackupData?, val problem: BackupProblem?)

private fun nowStamp(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

/** The name suggested for a new backup file, with today's date: bento-backup-2026-10-11.json. */
fun backupFileName(now: Date = Date()): String =
    "bento-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + ".json"

/** Keeps payloads from the front of [files] while their total stays within [maxBytes]; the rest are counted as skipped. */
fun fitShelf(files: List<ShelfFile>, maxBytes: Long): Pair<List<ShelfFile>, Int> {
    val kept = ArrayList<ShelfFile>()
    var total = 0L
    var skipped = 0
    for (f in files) {
        if (total + f.bytes.size <= maxBytes) {
            kept.add(f)
            total += f.bytes.size
        } else {
            skipped++
        }
    }
    return kept to skipped
}

/** The day a backup was made ("2026-10-11"), or null when the file doesn't say. */
fun backupDay(data: BackupData): String? {
    val s = data.savedAt
    return if (s.length >= 10 && s[4] == '-' && s[7] == '-' && s.substring(0, 10).all { it.isDigit() || it == '-' }) s.substring(0, 10) else null
}

/** The text of a backup file for [data]. [versionName] is the app version that makes it. */
fun backupToJson(data: BackupData, versionName: String, savedAt: String = nowStamp()): String {
    val shelf = JSONArray()
    for (f in data.shelf) {
        shelf.put(JSONObject().put("name", f.name).put("data", Base64.getEncoder().encodeToString(f.bytes)))
    }
    val o = JSONObject()
        .put("app", "Bento")
        .put("format", BACKUP_FORMAT)
        .put("version", versionName)
        .put("saved", savedAt)
        .put("consoles", JSONArray(profilesToJson(data.consoles)))
        .put("active", data.activeId)
        .put("home", data.home)
    data.bookmarks?.let { o.put("bookmarks", JSONArray(bookmarksToJson(it))) }
    if (data.adblockOn != null || data.adblockCustom != null) {
        val ads = JSONObject()
        data.adblockOn?.let { ads.put("on", it) }
        data.adblockCustom?.let { ads.put("custom", it) }
        o.put("adblock", ads)
    }
    o.put("shelf", shelf)
    data.chains?.let { o.put("chains", JSONArray(chainsToJson(it))) }
    data.pins?.let { all ->
        val pins = JSONObject()
        for ((id, folders) in all) if (folders.isNotEmpty()) pins.put(id, JSONArray(folders))
        o.put("pins", pins)
    }
    return o.toString(2)
}

/** Reads the text of a backup file. Anything missing from it is left as it is on the phone. */
fun parseBackup(text: String): BackupRead {
    val o = try {
        JSONObject(text)
    } catch (_: Exception) {
        return BackupRead(null, BackupProblem.NOT_A_BACKUP)
    }
    if (o.optString("app", "") != "Bento") return BackupRead(null, BackupProblem.NOT_A_BACKUP)
    if (o.optInt("format", 0) > BACKUP_FORMAT) return BackupRead(null, BackupProblem.TOO_NEW)

    // A name longer than the console button allows is cut, as typing it would have done
    val consoles = parseProfiles(o.optJSONArray("consoles")?.toString())
        .distinctBy { it.id }
        .map { if (it.name.length > PROFILE_NAME_MAX) it.copy(name = it.name.take(PROFILE_NAME_MAX)) else it }
    val shelf = ArrayList<ShelfFile>()
    val items = o.optJSONArray("shelf")
    if (items != null) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name", "")
            val bytes = try {
                Base64.getDecoder().decode(item.optString("data", ""))
            } catch (_: Exception) {
                null
            }
            if (name.isNotEmpty() && bytes != null && bytes.isNotEmpty()) shelf.add(ShelfFile(name, bytes))
        }
    }
    val ads = o.optJSONObject("adblock")
    val activeWanted = o.optString("active", "")
    val data = BackupData(
        consoles = consoles,
        activeId = if (consoles.any { it.id == activeWanted }) activeWanted else consoles.firstOrNull()?.id ?: "",
        home = o.optString("home", ""),
        bookmarks = if (o.has("bookmarks")) parseBookmarks(o.optJSONArray("bookmarks")?.toString()).take(BOOKMARKS_MAX) else null,
        adblockOn = if (ads != null && ads.has("on")) ads.optBoolean("on", true) else null,
        adblockCustom = if (ads != null && ads.has("custom")) ads.optString("custom", "") else null,
        shelf = shelf,
        chains = if (o.has("chains")) parseChains(o.optJSONArray("chains")?.toString()) else null,
        pins = o.optJSONObject("pins")?.let { readPins(it) },
        savedAt = o.optString("saved", "")
    )
    return BackupRead(data, null)
}

/** The pinned folders of each console in [o] (console id -> a list of folders); entries that are no list of folders are left out. */
private fun readPins(o: JSONObject): Map<String, List<String>> {
    val found = LinkedHashMap<String, List<String>>()
    for (id in o.keys()) {
        val folders = pinsFromJson(o.optJSONArray(id)?.toString())
        if (folders.isNotEmpty()) found[id] = folders
    }
    return found
}

/** Collects what is saved on the phone right now. */
fun collectBackup(ctx: Context): BackupData {
    AdBlock.load(ctx) // the switch and the list as saved, in case the browser has not been opened yet
    val consoles = ProfileStore.load(ctx)
    val shelfDir = shelfDir(ctx)
    val files = shelfList(shelfDir).mapNotNull { item ->
        try {
            ShelfFile(item.name, java.io.File(shelfDir, item.name).readBytes())
        } catch (_: Exception) {
            null
        }
    }
    val (kept, skipped) = fitShelf(files, BACKUP_SHELF_MAX_BYTES)
    return BackupData(
        consoles = consoles,
        activeId = ProfileStore.activeId(ctx, consoles),
        home = readPref(ctx, "home_url_v2", DEFAULT_HOME),
        bookmarks = BookmarkStore.load(ctx),
        adblockOn = AdBlock.enabled.value,
        adblockCustom = AdBlock.customText(ctx),
        shelf = kept,
        chains = ChainStore.load(ctx),
        pins = FilesStore.allPins(ctx, consoles.map { it.id }),
        skipped = skipped
    )
}

/**
 * Puts a backup on the phone: the consoles, homepage, bookmarks and ad list are replaced (a part the file doesn't have
 * stays as it is), and the payloads are added to the shelf (a payload with the same name is replaced; the others stay).
 * The screen has to be rebuilt afterwards. Returns how many payloads could not be stored.
 */
fun applyBackup(ctx: Context, data: BackupData): Int {
    if (data.consoles.isNotEmpty()) {
        ProfileStore.save(ctx, data.consoles)
        if (data.activeId.isNotEmpty()) ProfileStore.saveActive(ctx, data.activeId)
    }
    if (data.home.isNotBlank()) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("home_url_v2", data.home).apply()
    }
    data.bookmarks?.let { BookmarkStore.save(ctx, it) }
    // Chains are put among the ones on the phone (one with the same id is replaced), so none is lost
    data.chains?.let { ChainStore.save(ctx, mergeChains(ChainStore.load(ctx), it)) }
    // Pinned folders are set for the consoles that the file has some for; the other consoles keep theirs
    data.pins?.forEach { (id, folders) -> FilesStore.savePins(ctx, id, folders) }
    if (data.adblockOn != null || data.adblockCustom != null) {
        AdBlock.load(ctx)
        AdBlock.save(ctx, data.adblockOn ?: AdBlock.enabled.value, data.adblockCustom ?: AdBlock.customText(ctx))
    }
    var failed = 0
    val dir = shelfDir(ctx)
    for (f in data.shelf) {
        try {
            shelfAdd(dir, f.name, ByteArrayInputStream(f.bytes))
        } catch (_: Exception) {
            failed++
        }
    }
    return failed
}

// ---------------------------------------------------------------------------------------------
// Files and messages (the About screen uses these)
// ---------------------------------------------------------------------------------------------

/** "Consoles: 2 · Bookmarks: 5 · Payloads: 3" (and "Chains: 2" on a line of its own when there are chains): what [data] holds. */
fun backupContentsLine(data: BackupData): String {
    val line = AppText.get(R.string.backup_contents, data.consoles.size, data.bookmarks?.size ?: 0, data.shelf.size)
    val chains = data.chains?.size ?: 0
    return if (chains > 0) line + "\n" + AppText.get(R.string.backup_chains, chains) else line
}

/** The message for a file that could not be used as a backup. */
fun backupProblemText(problem: BackupProblem): String =
    AppText.get(if (problem == BackupProblem.TOO_NEW) R.string.backup_too_new else R.string.backup_bad_file)

/** Writes a backup of what is saved now to [uri]. Returns the message to show (in the app's language). */
fun writeBackupTo(ctx: Context, uri: Uri, versionName: String): String {
    return try {
        val data = collectBackup(ctx)
        val text = backupToJson(data, versionName)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: return phoneFileProblem()
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        var message = AppText.get(R.string.backup_saved) + "\n" + backupContentsLine(data)
        if (data.skipped > 0) message += "\n" + AppText.get(R.string.backup_skipped, data.skipped)
        message
    } catch (e: Exception) {
        failedText(e)
    }
}

/** What picking a file to restore gave: the backup to confirm, or the message to show instead. */
class BackupPick(val data: BackupData?, val message: String)

/** Reads the file at [uri] as a backup. */
fun pickBackup(ctx: Context, uri: Uri): BackupPick {
    return try {
        val input = ctx.contentResolver.openInputStream(uri) ?: return BackupPick(null, phoneFileProblem())
        val read = parseBackup(input.use { readLimited(it, BACKUP_READ_MAX_BYTES) })
        val data = read.data
        if (data != null) BackupPick(data, "") else BackupPick(null, backupProblemText(read.problem ?: BackupProblem.NOT_A_BACKUP))
    } catch (e: Exception) {
        BackupPick(null, failedText(e))
    }
}

/** The message after a restore: that it worked, and how many payloads could not be stored ([failedPayloads]). */
fun restoreMessage(failedPayloads: Int): String {
    var message = AppText.get(R.string.restore_done)
    if (failedPayloads > 0) message += "\n" + AppText.get(R.string.restore_payloads_failed, failedPayloads)
    return message
}

/**
 * A backup made by itself: when switched on, once a week when the app opens, into Downloads/Bento/Backups on the phone
 * (a new file each time, so older ones stay until you delete them).
 */
object AutoBackup {
    private const val EVERY_MS = 7L * 24 * 60 * 60 * 1000
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_backup", false)
    fun setOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_backup", on).apply()
    fun last(ctx: Context): Long = prefs(ctx).getLong("auto_backup_last", 0L)

    /** Makes a backup when one is due. Gives where it went, or null when none was made. Blocking. */
    fun runIfDue(ctx: Context, versionName: String, now: Long = System.currentTimeMillis()): String? {
        if (!isOn(ctx) || now - last(ctx) in 0 until EVERY_MS) return null
        val text = backupToJson(collectBackup(ctx), versionName)
        val where = saveTextToDownloads(ctx.contentResolver, "Bento", "Backups", backupFileName(), text) ?: return null
        prefs(ctx).edit().putLong("auto_backup_last", now).apply()
        return where
    }
}
