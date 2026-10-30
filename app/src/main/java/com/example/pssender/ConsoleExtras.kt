package com.example.pssender

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Two Files-tab helpers: how much space each installed game takes, and copying new screenshots and video clips from
// the console to the phone (on request, or by itself when Bento opens).
// ---------------------------------------------------------------------------------------------

/** How much each installed title takes on the console, counted over FTP. */
object GameSizes {
    /** Bytes per title ID: the game and, for a PS4 title, its installed patch. */
    var sizes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    var counting by mutableStateOf(false)
        private set
    var done by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set

    /** Some sizes are only a lower bound (a game with more files than are counted). */
    var partial by mutableStateOf(false)
        private set
    private var countedFor = ""

    /** Files counted per game at most (a PS5 game can have tens of thousands). */
    private const val MAX_FILES = 40_000

    fun keepOnly(key: String) {
        if (countedFor.isNotEmpty() && key != countedFor) {
            sizes = emptyMap()
            countedFor = ""
        }
    }

    val totalBytes: Long get() = sizes.values.sum()

    /**
     * Counts [apps]. A PS4 title's game and patch are single package files, so one listing each is enough; a PS5
     * title's folder is walked.
     */
    suspend fun count(key: String, s: FtpSettings, apps: List<AppEntry>) {
        if (counting) return
        if (key != countedFor) sizes = emptyMap()
        countedFor = key
        counting = true
        partial = false
        done = 0
        total = apps.size
        try {
            val ps4 = apps.filter { !ConsolePaths.isPs5Title(it.id) }.map { it.id }
            if (ps4.isNotEmpty()) {
                val dirs = ps4.flatMap { listOf(joinPath(ConsolePaths.APPS, it), joinPath(ConsolePaths.PATCHES, it)) }
                val lists = withContext(Dispatchers.IO) { ftpListMany(s, dirs) }
                val found = ps4.associateWith { id ->
                    listOf(ConsolePaths.APPS, ConsolePaths.PATCHES).sumOf { root -> lists[joinPath(root, id)].orEmpty().filter { !it.isDir }.sumOf { it.size.coerceAtLeast(0) } }
                }
                if (countedFor == key) sizes = sizes + found
                done += ps4.size
            }
            for (a in apps.filter { ConsolePaths.isPs5Title(it.id) }) {
                if (countedFor != key) break
                val tree = withContext(Dispatchers.IO) { ftpTree(s, joinPath(ConsolePaths.APPS, a.id), MAX_FILES) }
                if (tree.truncated) partial = true
                if (countedFor == key) sizes = sizes + (a.id to tree.files.values.sumOf { it.coerceAtLeast(0) })
                done++
            }
        } finally {
            counting = false
        }
    }
}

/** Screenshots and video clips copied from the console to the phone, without copying any twice. */
object CaptureSync {
    private val MEDIA = Regex("""\.(jpe?g|png|webp|mp4|webm|mkv|mov)$""", RegexOption.IGNORE_CASE)

    /** Folder inside Downloads/PS5 (or PS4) the captures go into, with a folder per game below it. */
    const val FOLDER = "Captures"

    /** At most this many files are looked through. */
    private const val MAX_FILES = 20_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun key(profileId: String) = "captures_copied_$profileId"

    fun autoOn(ctx: Context, profileId: String): Boolean = prefs(ctx).getBoolean("captures_auto_$profileId", false)
    fun setAuto(ctx: Context, profileId: String, on: Boolean) = prefs(ctx).edit().putBoolean("captures_auto_$profileId", on).apply()

    /** The captures already copied for [profileId] (paths on the console). */
    private fun copied(ctx: Context, profileId: String): Set<String> =
        (prefs(ctx).getString(key(profileId), "") ?: "").split('\n').filter { it.isNotEmpty() }.toSet()

    /** Notes [paths] as copied (the list keeps the newest 5,000). */
    fun markCopied(ctx: Context, profileId: String, paths: Collection<String>) {
        val all = (copied(ctx, profileId) + paths).toList().takeLast(5_000)
        prefs(ctx).edit().putString(key(profileId), all.joinToString("\n")).apply()
    }

    /** Forgets what was copied, so the next copy takes everything again. */
    fun reset(ctx: Context, profileId: String) = prefs(ctx).edit().remove(key(profileId)).apply()

    /**
     * The captures on the console not copied yet, as downloads into Downloads/…/Captures/<game folder>. Blocking. The
     * first time ever, [firstTimeAll] decides whether everything there counts as new (on request) or as already seen (on
     * its own when the app opens, so it doesn't start copying a whole library unasked).
     */
    fun plan(ctx: Context, s: FtpSettings, profileId: String, firstTimeAll: Boolean): List<PlannedFile> {
        val tree = ftpTree(s, ConsolePaths.MEDIA, MAX_FILES)
        if (!tree.exists) return emptyList()
        val media = tree.files.filter { (rel, _) -> MEDIA.containsMatchIn(rel) && !rel.contains("/.") }
        val first = !prefs(ctx).contains(key(profileId))
        if (first && !firstTimeAll) {
            markCopied(ctx, profileId, media.keys.map { joinPath(ConsolePaths.MEDIA, it) })
            return emptyList()
        }
        val done = copied(ctx, profileId)
        return media.mapNotNull { (rel, size) ->
            val remote = joinPath(ConsolePaths.MEDIA, rel)
            if (remote in done) return@mapNotNull null
            val folder = rel.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
            PlannedFile(remote, if (folder.isEmpty()) FOLDER else "$FOLDER/$folder", rel.substringAfterLast('/'), size)
        }.sortedBy { it.remotePath }
    }
}

/**
 * Which payloads on the shelf were checked against a published checksum when they were downloaded, and which list it
 * came from. A file is only shown as verified while it still has that checksum.
 */
object ShelfChecks {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun markVerified(ctx: Context, name: String, sha256: String, by: String) {
        prefs(ctx).edit().putString("shelf_verified_$name", "$sha256\n$by").apply()
    }

    fun forget(ctx: Context, name: String) = prefs(ctx).edit().remove("shelf_verified_$name").apply()

    /** The list that verified [name] and its checksum, or null. */
    fun verified(ctx: Context, name: String): Pair<String, String>? {
        val v = prefs(ctx).getString("shelf_verified_$name", null) ?: return null
        return v.substringBefore('\n') to v.substringAfter('\n', "")
    }

    /** The SHA-256 of [file], or "" when it can't be read. Blocking. */
    fun sha256Of(file: java.io.File): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }
}
