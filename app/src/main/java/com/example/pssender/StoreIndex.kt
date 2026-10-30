package com.example.pssender // change to match your project's package name

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Where Get payloads finds payloads, beyond the few built into the app:
//  - Bento's own list (payloads/index.json in Bento's repository): vetted entries, so a new payload can be added to every
//    copy of the app without an app update.
//  - The community list kept by the PS5 Payloads Mirror (itsPLK/ps5-payloads-mirror): dozens of PS5 payloads with the
//    makers' descriptions, versions and checksums, refreshed daily by its maintainer.
//  - A GitHub search, only when asked: repositories that call themselves PS5/PS4 payloads. Nobody has reviewed these.
// Lists are kept for a few hours so starting the app does not cost a download each time.
// ---------------------------------------------------------------------------------------------

/** A payload from the community list or a GitHub search. */
data class FoundPayload(
    val name: String,
    /** "owner/repo" on GitHub, or "" when it lives elsewhere. */
    val repo: String,
    val description: String,
    val page: String,
    val category: String = "",
    val version: String = "",
    val updated: String = "",
    /** The file straight from its maker's release, when the list has it. */
    val download: String = "",
    /** The same file kept by the list (a plain .elf/.bin even when the maker ships a zip). */
    val mirror: String = "",
    val sha256: String = "",
    val fileName: String = "",
    /** GitHub stars (search results only), or -1. */
    val stars: Int = -1
) {
    /** The key its installed version is remembered under (the same as a built-in entry with the same repo). */
    val key: String get() = repo.ifEmpty { "list:" + name.lowercase(Locale.ROOT) }
}

object StoreIndex {
    const val CURATED_URL = "https://raw.githubusercontent.com/DubDeezo-hub/Bento-Beta/main/payloads/index.json"
    const val COMMUNITY_URL = "https://raw.githubusercontent.com/itsPLK/ps5-payloads-mirror/main/payloads.json"
    const val COMMUNITY_PAGE = "https://github.com/itsPLK/ps5-payloads-mirror"

    /** Lists are fetched again after this long (or at once with "Check for new payloads"). */
    private const val KEEP_MS = 6L * 60 * 60 * 1000

    /** GitHub search: at most this many results, with at least this many stars, pushed within this many days. */
    private const val SEARCH_MAX = 20
    private const val SEARCH_MIN_STARS = 5
    private const val SEARCH_DAYS = 540

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // --- reading the lists ---

    /** "owner/repo" from a GitHub address ("https://github.com/owner/repo/releases" and the like), else "". */
    fun repoOf(url: String): String {
        val m = Regex("""^https?://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)""").find(url.trim()) ?: return ""
        return m.groupValues[1] + "/" + m.groupValues[2].removeSuffix(".git")
    }

    /** Bento's own list: entries to add to (or change in) the built-in ones. */
    fun parseCurated(json: String, lang: String = Locale.getDefault().language): List<StoreEntry> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        return (root["payloads"] as? List<*>).orEmpty().mapNotNull { x ->
            val o = x as? Map<*, *> ?: return@mapNotNull null
            val repo = (o["repo"] as? String)?.trim().orEmpty()
            val name = (o["name"] as? String)?.trim().orEmpty()
            val file = (o["file"] as? String)?.trim().orEmpty()
            val console = when ((o["console"] as? String)?.lowercase()) {
                "ps5" -> ConsoleType.PS5
                "ps4" -> ConsoleType.PS4
                else -> return@mapNotNull null
            }
            if (!repo.contains('/') || name.isEmpty()) return@mapNotNull null
            val about = when (val a = o["about"]) {
                is String -> a
                is Map<*, *> -> (a[lang] as? String) ?: (a["en"] as? String) ?: ""
                else -> ""
            }
            StoreEntry(
                repo = repo, name = name, about = 0, console = console, file = file,
                anyFirmware = o["anyFirmware"] == true, aboutText = about.trim(), hidden = o["hidden"] == true
            )
        }
    }

    /** The built-in entries with Bento's list applied: changes to known ones, new ones after them, hidden ones gone. */
    fun merge(builtIn: List<StoreEntry>, curated: List<StoreEntry>): List<StoreEntry> {
        val byRepo = curated.associateBy { it.repo.lowercase() }
        val known = builtIn.map { it.repo.lowercase() }.toSet()
        val kept = builtIn.mapNotNull { b ->
            val c = byRepo[b.repo.lowercase()] ?: return@mapNotNull b
            if (c.hidden) null
            else b.copy(name = c.name, file = c.file.ifEmpty { b.file }, anyFirmware = c.anyFirmware || b.anyFirmware)
        }
        return kept + curated.filter { !it.hidden && it.repo.lowercase() !in known }
    }

    /** The community list (PS5 payloads). */
    fun parseCommunity(json: String): List<FoundPayload> {
        val root = MiniJson.parse(json)
        val list = (root as? List<*>) ?: ((root as? Map<*, *>)?.get("payloads") as? List<*>) ?: return emptyList()
        return list.mapNotNull { x ->
            val o = x as? Map<*, *> ?: return@mapNotNull null
            val name = (o["name"] as? String)?.trim().orEmpty()
            val source = (o["source"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            FoundPayload(
                name = name,
                repo = repoOf(source),
                description = (o["description"] as? String)?.trim().orEmpty(),
                page = source,
                category = (o["category"] as? String)?.trim().orEmpty(),
                version = (o["version"] as? String)?.trim().orEmpty(),
                updated = (o["last_update"] as? String)?.trim().orEmpty().take(10),
                download = (o["source_direct"] as? String)?.trim().orEmpty(),
                mirror = (o["url"] as? String)?.trim().orEmpty(),
                sha256 = (o["checksum"] as? String)?.trim()?.lowercase().orEmpty(),
                fileName = (o["filename"] as? String)?.trim().orEmpty()
            )
        }
    }

    /** GitHub's search reply as payloads (only plausible ones: not forks, not archived). */
    fun parseSearch(json: String): List<FoundPayload> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        return (root["items"] as? List<*>).orEmpty().mapNotNull { x ->
            val o = x as? Map<*, *> ?: return@mapNotNull null
            if (o["fork"] == true || o["archived"] == true) return@mapNotNull null
            val full = o["full_name"] as? String ?: return@mapNotNull null
            FoundPayload(
                name = (o["name"] as? String) ?: full.substringAfter('/'),
                repo = full,
                description = (o["description"] as? String)?.trim().orEmpty(),
                page = (o["html_url"] as? String) ?: "https://github.com/$full",
                updated = (o["pushed_at"] as? String).orEmpty().take(10),
                stars = (o["stargazers_count"] as? Number)?.toInt() ?: 0
            )
        }
    }

    // --- fetching, with a short-lived copy ---

    /** The text at [url], from the kept copy when it is fresh enough (or when the network fails). Blocking. */
    private fun fetch(ctx: Context, key: String, url: String, force: Boolean): String? {
        val p = prefs(ctx)
        val kept = p.getString(key, null)
        val time = kept?.substringBefore('\n')?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (!force && kept != null && now - time in 0..KEEP_MS) return kept.substringAfter('\n')
        return try {
            val reply = webGet(url, true, "application/json", 10_000)
            if (reply.code != 200) return kept?.substringAfter('\n')
            p.edit().putString(key, "$now\n" + reply.body).apply()
            reply.body
        } catch (_: Exception) {
            kept?.substringAfter('\n')
        }
    }

    /** The store's entries: the built-in ones with Bento's list applied. Blocking. */
    fun entries(ctx: Context, force: Boolean = false): List<StoreEntry> {
        val text = fetch(ctx, "store_index_curated", CURATED_URL, force) ?: return Store.BUILT_IN
        return merge(Store.BUILT_IN, parseCurated(text))
    }

    /** The community list for [type], without what is already among [entries]. Blocking; null when it can't be read. */
    fun community(ctx: Context, type: ConsoleType, entries: List<StoreEntry>, force: Boolean = false): List<FoundPayload>? {
        if (type != ConsoleType.PS5) return emptyList()
        val text = fetch(ctx, "store_index_community", COMMUNITY_URL, force) ?: return null
        val known = entries.map { it.repo.lowercase() }.toSet()
        return parseCommunity(text).filter { it.repo.isEmpty() || it.repo.lowercase() !in known }
    }

    /** Searches GitHub for [type] payloads not among [exclude] (repos, any case). Blocking; throws when GitHub refuses. */
    fun searchGitHub(type: ConsoleType, exclude: Set<String>): List<FoundPayload> {
        val since = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            .format(java.util.Date(System.currentTimeMillis() - SEARCH_DAYS * 24L * 60 * 60 * 1000))
        val sys = type.id
        val filters = "fork:false archived:false stars:>=$SEARCH_MIN_STARS pushed:>=$since"
        val queries = listOf("topic:$sys-payload $filters", "$sys payload in:name,description $filters")
        val found = LinkedHashMap<String, FoundPayload>()
        var lastError: Exception? = null
        for (q in queries) {
            try {
                val url = "https://api.github.com/search/repositories?sort=stars&order=desc&per_page=30&q=" +
                    java.net.URLEncoder.encode(q, "UTF-8")
                val reply = webGet(url, true, "application/vnd.github+json", 12_000)
                if (reply.code == 403 || reply.code == 429) throw java.io.IOException(AppText.get(R.string.store_github_busy))
                if (reply.code != 200) throw java.io.IOException("GitHub: HTTP ${reply.code}")
                for (f in parseSearch(reply.body)) found.putIfAbsent(f.repo.lowercase(), f)
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (found.isEmpty() && lastError != null) throw lastError
        val skip = exclude.map { it.lowercase() }.toSet()
        return found.values.filter { it.repo.lowercase() !in skip }.sortedByDescending { it.stars }.take(SEARCH_MAX)
    }

    // --- what is new since last time ---

    /** What a payload is known by when telling new ones from old ones. */
    fun idOf(e: StoreEntry): String = e.repo.lowercase(Locale.ROOT)
    fun idOf(f: FoundPayload): String = f.key.lowercase(Locale.ROOT)

    /**
     * The names in [names] not seen before for [type]. The first time ever, everything counts as seen (so the very
     * first start does not call the whole list new).
     */
    fun newOnes(ctx: Context, type: ConsoleType, names: Collection<String>): List<String> {
        val key = "store_seen_" + type.id
        val raw = prefs(ctx).getString(key, null)
        if (raw == null) {
            markSeen(ctx, type, names)
            return emptyList()
        }
        val seen = raw.split('\n').toSet()
        return names.filter { it !in seen }
    }

    fun markSeen(ctx: Context, type: ConsoleType, names: Collection<String>) {
        val key = "store_seen_" + type.id
        val before = prefs(ctx).getString(key, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        prefs(ctx).edit().putString(key, (before + names).distinct().takeLast(500).joinToString("\n")).apply()
    }

    // --- checking a download ---

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
