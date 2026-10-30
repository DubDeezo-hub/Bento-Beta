package com.example.pssender

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// ---------------------------------------------------------------------------------------------
// The payload store: well-known homebrew payloads, fetched from their own GitHub Releases pages straight onto the
// shelf, with a notice when a newer version is out. Bento ships none of them; it only knows where they are published.
// ---------------------------------------------------------------------------------------------

/** One payload the store knows: where its releases are and which file in them to take. */
data class StoreEntry(
    val repo: String,
    val name: String,
    /** What it is for (a string resource). */
    val about: Int,
    /** The console it runs on. */
    val console: ConsoleType,
    /** The file name to take from a release (or from the zip in it); case does not matter. */
    val file: String,
    /** A plain tool (a server, a logger) that runs on any firmware where payloads can be loaded at all. */
    val anyFirmware: Boolean = false,
    /** What it is for, as text (entries from Bento's online list, which has no string resource). */
    val aboutText: String = "",
    /** Taken off the list by Bento's online list. */
    val hidden: Boolean = false
)

/** A file in a release. */
data class StoreAsset(val name: String, val url: String, val size: Long, /** "sha256:…" as GitHub lists it, or "". */ val digest: String = "")

/** The newest release of a payload. */
data class StoreRelease(val tag: String, val page: String, val date: String, val assets: List<StoreAsset>, val notes: String = "")

object Store {
    /** The payloads built into the app; Bento's online list can add to them (see [StoreIndex]). */
    val BUILT_IN = listOf(
        StoreEntry("etaHEN/etaHEN", "etaHEN", R.string.store_about_etahen, ConsoleType.PS5, "etaHEN.bin"),
        StoreEntry("Pharaoh2k/ps5debug-NG", "ps5debug-NG", R.string.store_about_ps5debug, ConsoleType.PS5, "ps5debug-NG.elf"),
        StoreEntry("ps5-payload-dev/ftpsrv", "ftpsrv", R.string.store_about_ftpsrv, ConsoleType.PS5, "%s.elf", anyFirmware = true),
        StoreEntry("ps5-payload-dev/websrv", "websrv", R.string.store_about_websrv, ConsoleType.PS5, "%s.elf", anyFirmware = true),
        StoreEntry("ps5-payload-dev/shsrv", "shsrv", R.string.store_about_shsrv, ConsoleType.PS5, "%s.elf", anyFirmware = true),
        StoreEntry("ps5-payload-dev/klogsrv", "klogsrv", R.string.store_about_klogsrv, ConsoleType.PS5, "%s.elf", anyFirmware = true),
        StoreEntry("EchoStretch/kstuff", "kstuff", R.string.store_about_kstuff, ConsoleType.PS5, "kstuff.elf"),
        StoreEntry("Pharaoh2k/ps4debug-NG", "ps4debug-NG", R.string.store_about_ps4debug, ConsoleType.PS4, "ps4debug-ng.bin"),
        StoreEntry("GoldHEN/GoldHEN_Releases", "GoldHEN", R.string.store_about_goldhen, ConsoleType.PS4, "goldhen.bin")
    )

    /** The payloads the store shows now: [BUILT_IN] with Bento's online list applied once it has been read. */
    var entries by mutableStateOf(BUILT_IN)

    private val PAYLOAD = Regex("""\.(elf|bin)$""", RegexOption.IGNORE_CASE)

    /** The newest release (not a draft) in GitHub's list of releases, or null. */
    fun parseReleases(json: String): StoreRelease? {
        val list = MiniJson.parse(json) as? List<*> ?: return null
        for (r in list) {
            val o = r as? Map<*, *> ?: continue
            if (o["draft"] == true) continue
            val assets = (o["assets"] as? List<*>).orEmpty().mapNotNull { a ->
                val m = a as? Map<*, *> ?: return@mapNotNull null
                val name = m["name"] as? String ?: return@mapNotNull null
                val url = m["browser_download_url"] as? String ?: return@mapNotNull null
                StoreAsset(name, url, (m["size"] as? Number)?.toLong() ?: -1L, (m["digest"] as? String).orEmpty())
            }
            return StoreRelease(
                tag = o["tag_name"] as? String ?: "",
                page = o["html_url"] as? String ?: "",
                date = (o["published_at"] as? String ?: "").take(10),
                assets = assets,
                notes = o["body"] as? String ?: ""
            )
        }
        return null
    }

    /** The file to take: the named one, else the only payload, else a zip that may hold it. Null when there is none. */
    fun pick(entry: StoreEntry, release: StoreRelease): StoreAsset? {
        val a = release.assets
        a.firstOrNull { it.name.equals(entry.file, true) }?.let { return it }
        val payloads = a.filter { PAYLOAD.containsMatchIn(it.name) }
        payloads.firstOrNull { it.name.contains(entry.file.substringBefore('.'), true) }?.let { return it }
        if (payloads.size == 1) return payloads[0]
        val zips = a.filter { it.name.endsWith(".zip", true) }
        return zips.firstOrNull { it.name.contains(entry.name, true) } ?: zips.firstOrNull() ?: payloads.firstOrNull()
    }

    /** From a zip, the entry named like [wanted] (else the only .elf/.bin in it): its name and bytes. */
    fun fromZip(input: InputStream, wanted: String, maxBytes: Long = SHELF_MAX_BYTES): Pair<String, ByteArray>? {
        val found = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val base = e.name.substringAfterLast('/')
                if (e.isDirectory || !PAYLOAD.containsMatchIn(base)) continue
                val bytes = readCapped(zip, maxBytes) ?: continue
                if (base.equals(wanted, true)) return base to bytes
                found.add(base to bytes)
            }
        }
        return found.firstOrNull { it.first.contains(wanted.substringBefore('.'), true) } ?: found.singleOrNull()
    }

    private fun readCapped(input: InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    // --- remembered: which version of each payload was put on the shelf ---

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun installedTag(ctx: Context, e: StoreEntry): String = installedTag(ctx, e.repo)
    fun installedFile(ctx: Context, e: StoreEntry): String = installedFile(ctx, e.repo)
    fun installedTag(ctx: Context, key: String): String = prefs(ctx).getString("store_tag_$key", "") ?: ""
    fun installedFile(ctx: Context, key: String): String = prefs(ctx).getString("store_file_$key", "") ?: ""
    private fun remember(ctx: Context, key: String, tag: String, file: String) =
        prefs(ctx).edit().putString("store_tag_$key", tag).putString("store_file_$key", file).apply()

    /** Puts [added] in place of the version kept under [key] before (the shelf keeps one copy) and remembers it. */
    private fun replaceOld(ctx: Context, key: String, tag: String, added: ShelfAdded) {
        val before = installedFile(ctx, key)
        if (before.isNotEmpty() && before != added.name) File(shelfDir(ctx), before).delete()
        remember(ctx, key, tag, added.name)
    }

    /** The bytes at [url] (at most twice the shelf's limit). Blocking. */
    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Bento-store")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            if (c.contentLengthLong > SHELF_MAX_BYTES * 2) throw ShelfTooLarge(SHELF_MAX_BYTES)
            return c.inputStream.use { readCapped(it, SHELF_MAX_BYTES * 2) } ?: throw ShelfTooLarge(SHELF_MAX_BYTES)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Puts a payload from the community list on the shelf: from its maker's release when that is a plain payload file,
     * else from the list's own copy. Either way the bytes must match the list's checksum. Gives the message to show.
     */
    fun installFound(ctx: Context, f: FoundPayload): String {
        val urls = ArrayList<String>()
        if (f.download.isNotEmpty() && PAYLOAD.containsMatchIn(f.download.substringBefore('?'))) urls.add(f.download)
        if (f.mirror.isNotEmpty()) urls.add(f.mirror)
        if (f.download.isNotEmpty() && f.download !in urls) urls.add(f.download)
        if (urls.isEmpty()) throw java.io.IOException(AppText.get(R.string.store_no_file))
        var mismatch = false
        var problem: Exception? = null
        for (url in urls) {
            val bytes = try { download(url) } catch (e: ShelfTooLarge) { throw e } catch (e: Exception) { problem = e; continue }
            val zip = url.substringBefore('?').endsWith(".zip", true)
            // The list's checksum is of the payload file itself, so a zip is checked after unpacking
            val unpacked = if (zip) fromZip(bytes.inputStream(), f.fileName.ifEmpty { f.name }) else url.substringBefore('?').substringAfterLast('/') to bytes
            if (unpacked == null) {
                problem = java.io.IOException(AppText.get(R.string.store_no_payload_in_zip))
                continue
            }
            val (name, payload) = unpacked
            if (f.sha256.isNotEmpty() && StoreIndex.sha256(payload) != f.sha256) {
                mismatch = true
                continue
            }
            val added = shelfAdd(shelfDir(ctx), name, payload.inputStream())
            if (f.sha256.isNotEmpty()) ShelfChecks.markVerified(ctx, added.name, f.sha256, "PS5 Payloads Mirror")
            replaceOld(ctx, f.key, f.version.ifEmpty { f.updated }, added)
            return AppText.get(R.string.store_added, added.name, f.version.ifEmpty { f.updated })
        }
        if (mismatch) throw java.io.IOException(AppText.get(R.string.store_checksum_bad))
        throw problem ?: java.io.IOException(AppText.get(R.string.store_no_file))
    }

    /** The newest release of [e], from GitHub. Blocking. */
    fun latest(e: StoreEntry): StoreRelease {
        val reply = webGet("https://api.github.com/repos/${e.repo}/releases?per_page=5", true, "application/vnd.github+json")
        if (reply.code == 404) throw java.io.IOException(AppText.get(R.string.store_no_releases))
        if (reply.code != 200) throw java.io.IOException("GitHub: HTTP ${reply.code}")
        return parseReleases(reply.body) ?: throw java.io.IOException(AppText.get(R.string.store_no_releases))
    }

    /**
     * The firmware lines of [e]'s README, kept for a few days so opening the store does not cost an extra GitHub call
     * per payload each time. Blocking; empty when the README cannot be read.
     */
    fun readmeFirmwareLines(ctx: Context, e: StoreEntry): String {
        val p = prefs(ctx)
        val key = "store_fwtext_" + e.repo
        val kept = p.getString(key, null)
        val now = System.currentTimeMillis()
        if (kept != null) {
            val time = kept.substringBefore('\n').toLongOrNull() ?: 0L
            if (now - time in 0..README_KEEP_MS) return kept.substringAfter('\n', "")
        }
        return try {
            val reply = webGet("https://api.github.com/repos/${e.repo}/readme", true, "application/vnd.github.raw")
            if (reply.code != 200 && reply.code != 404) return kept?.substringAfter('\n', "") ?: ""
            val lines = if (reply.code == 200) FirmwareSupport.relevantLines(reply.body).joinToString("\n").take(8000) else ""
            p.edit().putString(key, "$now\n$lines").apply()
            lines
        } catch (_: Exception) {
            kept?.substringAfter('\n', "") ?: ""
        }
    }

    /** What [e]'s README and the notes of [release] say about firmware. Blocking. */
    fun firmwareClaims(ctx: Context, e: StoreEntry, release: StoreRelease?): FwClaims =
        FirmwareSupport.claims(readmeFirmwareLines(ctx, e) + "\n" + (release?.notes ?: ""))

    private const val README_KEEP_MS = 3L * 24 * 60 * 60 * 1000

    /** Downloads [asset] of [release] onto the shelf. Gives the message to show. Blocking. */
    fun install(ctx: Context, e: StoreEntry, release: StoreRelease, asset: StoreAsset): String {
        val c = URL(asset.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Bento-store")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val len = c.contentLengthLong
            if (len > SHELF_MAX_BYTES * 2) throw ShelfTooLarge(SHELF_MAX_BYTES)
            val dir = shelfDir(ctx)
            val added = c.inputStream.use { input ->
                if (asset.name.endsWith(".zip", true)) {
                    val (name, bytes) = fromZip(input, e.file) ?: throw java.io.IOException(AppText.get(R.string.store_no_payload_in_zip))
                    shelfAdd(dir, name, bytes.inputStream())
                } else {
                    shelfAdd(dir, asset.name, input)
                }
            }
            // A file GitHub lists a checksum for must match it (a zip is checked before unpacking, by GitHub's own digest)
            val want = asset.digest.removePrefix("sha256:").lowercase()
            if (want.length == 64 && !asset.name.endsWith(".zip", true)) {
                val got = StoreIndex.sha256(File(dir, added.name).readBytes())
                if (got != want) {
                    File(dir, added.name).delete()
                    throw java.io.IOException(AppText.get(R.string.store_checksum_bad_github))
                }
                ShelfChecks.markVerified(ctx, added.name, got, "GitHub")
            }
            // An older version under another name goes, so the shelf keeps one copy
            replaceOld(ctx, e.repo, release.tag, added)
            return AppText.get(R.string.store_added, added.name, release.tag)
        } finally {
            c.disconnect()
        }
    }
}

/** What the store shows for one payload. */
private class StoreRow(
    val entry: StoreEntry,
    val release: StoreRelease? = null,
    val problem: String = "",
    val loading: Boolean = true,
    val claims: FwClaims? = null
)

/** A GitHub search result and, once looked up, its newest release. */
private class FoundRow(
    val found: FoundPayload,
    val entry: StoreEntry,
    val release: StoreRelease? = null,
    val problem: String = "",
    val loading: Boolean = false
)

@Composable
fun StoreDialog(type: ConsoleType, onAdded: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = Store.entries
    val list = remember(type, entries) { entries.filter { it.console == type } }
    var rows by remember(type, entries) { mutableStateOf(list.map { StoreRow(it) }) }
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val profileId = ActiveConsole.id
    var firmware by remember(profileId) { mutableStateOf(ConsoleFirmware.get(ctx, profileId)) }
    var editingFw by remember { mutableStateOf(false) }
    var fwText by remember { mutableStateOf("") }
    // The community list and the GitHub search, and which payloads are new since the store was last opened
    var community by remember(type) { mutableStateOf<List<FoundPayload>?>(null) }
    var communityFailed by remember(type) { mutableStateOf(false) }
    var fresh by remember(type) { mutableStateOf<Set<String>>(emptySet()) }
    var refreshRun by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var github by remember(type) { mutableStateOf<List<FoundRow>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var githubProblem by remember(type) { mutableStateOf("") }

    LaunchedEffect(type, refreshRun) {
        val force = refreshRun > 0
        refreshing = force
        val (es, comm) = withContext(Dispatchers.IO) {
            val es = StoreIndex.entries(ctx, force)
            es to StoreIndex.community(ctx, type, es, force)
        }
        Store.entries = es
        community = comm
        communityFailed = comm == null && type == ConsoleType.PS5
        val ids = es.filter { it.console == type }.map { StoreIndex.idOf(it) } + comm.orEmpty().map { StoreIndex.idOf(it) }
        fresh = fresh + StoreIndex.newOnes(ctx, type, ids)
        StoreIndex.markSeen(ctx, type, ids)
        StoreUpdates.seen()
        refreshing = false
        if (force) message = AppText.get(R.string.store_refreshed)
    }
    LaunchedEffect(type, list) {
        list.forEachIndexed { i, e ->
            val row = withContext(Dispatchers.IO) {
                try {
                    val r = Store.latest(e)
                    StoreRow(e, r, loading = false, claims = if (e.anyFirmware) null else Store.firmwareClaims(ctx, e, r))
                } catch (x: Exception) {
                    StoreRow(e, problem = x.message ?: x.javaClass.simpleName, loading = false)
                }
            }
            rows = rows.toMutableList().also { if (i < it.size) it[i] = row }
        }
    }
    val fwNumber = ConsoleFirmware.number(firmware)

    /** Starts a download; [work] gives the message to show. */
    fun get(key: String, name: String, work: () -> String) {
        busy = key
        message = AppText.get(R.string.store_downloading, name)
        scope.launch {
            message = withContext(Dispatchers.IO) {
                try {
                    work()
                } catch (x: ShelfTooLarge) {
                    shelfTooLargeMessage(x.limit)
                } catch (x: Exception) {
                    failedText(x)
                }
            }
            busy = ""
            onAdded()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { TipTitle(str(R.string.store_title, type.model), Tip.STORE) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(str(R.string.store_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !refreshing && busy.isEmpty(), onClick = { refreshRun++ }) {
                        Text(str(if (refreshing) R.string.store_refreshing else R.string.store_refresh))
                    }
                }
                // The console's firmware, which the firmware notes below are measured against
                if (editingFw) {
                    val ok = ConsoleFirmware.normalize(fwText) != null
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = fwText,
                            onValueChange = { fwText = it.take(6) },
                            singleLine = true,
                            label = { Text(str(R.string.store_fw_label)) },
                            placeholder = { Text("10.40") },
                            isError = fwText.isNotBlank() && !ok,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(enabled = ok || fwText.isBlank(), onClick = {
                            if (fwText.isBlank()) ConsoleFirmware.clear(ctx, profileId) else ConsoleFirmware.set(ctx, profileId, fwText)
                            firmware = ConsoleFirmware.get(ctx, profileId)
                            editingFw = false
                        }) { Text(str(R.string.action_save)) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TipTitle(
                            if (firmware.isEmpty()) str(R.string.store_fw_none) else str(R.string.store_fw_console, firmware),
                            Tip.FIRMWARE,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (profileId.isNotEmpty()) {
                            TextButton(onClick = {
                                fwText = firmware
                                editingFw = true
                            }) { Text(str(if (firmware.isEmpty()) R.string.action_set else R.string.action_edit)) }
                        }
                    }
                }
                if (message.isNotEmpty()) {
                    Text(message, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    HelpLink(message)
                }

                // --- Bento's picks ---
                SectionTitle(str(R.string.store_section_picks))
                rows.forEach { row ->
                    val e = row.entry
                    val rel = row.release
                    val have = Store.installedTag(ctx, e)
                    val asset = rel?.let { Store.pick(e, it) }
                    val fit = when {
                        e.anyFirmware -> FwFit.ANY
                        row.claims == null || fwNumber == null -> null
                        else -> row.claims.fit(fwNumber)
                    }
                    PayloadCard(e.name, StoreIndex.idOf(e) in fresh, if (e.aboutText.isNotEmpty()) e.aboutText else if (e.about != 0) str(e.about) else "", dim = fit == FwFit.NOT_LISTED) {
                        val state = when {
                            row.loading -> str(R.string.store_checking)
                            row.problem.isNotEmpty() -> str(R.string.store_problem, row.problem)
                            rel == null -> ""
                            have.isEmpty() -> str(R.string.store_latest, rel.tag, rel.date)
                            have == rel.tag -> str(R.string.store_have, have)
                            else -> str(R.string.store_update, have, rel.tag)
                        }
                        StateLine(state, highlight = rel != null && have.isNotEmpty() && have != rel.tag)
                        firmwareNote(e, row.claims, fit, firmware)?.let { (text, good) -> FirmwareLine(text, good) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (rel != null && asset != null) {
                                Button(enabled = busy.isEmpty(), onClick = { get(e.repo, asset.name) { Store.install(ctx, e, rel, asset) } }) {
                                    Text(str(if (have.isNotEmpty() && have != rel.tag) R.string.store_get_update else R.string.store_get))
                                }
                            } else if (rel != null) {
                                Text(str(R.string.store_no_file), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                            }
                            TextButton(onClick = { openLink(ctx, rel?.page?.ifEmpty { null } ?: "https://github.com/${e.repo}/releases") }) {
                                Text(str(R.string.store_page))
                            }
                        }
                    }
                }

                // --- the community list (PS5) ---
                if (type == ConsoleType.PS5) {
                    SectionTitle(str(R.string.store_section_community))
                    Text(str(R.string.store_community_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { openLink(ctx, StoreIndex.COMMUNITY_PAGE) }) { Text(str(R.string.store_community_page)) }
                    val all = community
                    when {
                        communityFailed -> Text(str(R.string.store_community_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        all == null -> Text(str(R.string.store_checking), style = MaterialTheme.typography.bodySmall)
                        else -> {
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                singleLine = true,
                                label = { Text(str(R.string.store_filter)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            val q = filter.trim()
                            val shown = all.filter { q.isEmpty() || it.name.contains(q, true) || it.description.contains(q, true) || it.category.contains(q, true) }
                            // New ones first, then by category as the list sorts them
                            shown.sortedWith(compareBy<FoundPayload>({ if (StoreIndex.idOf(it) in fresh) 0 else 1 }, { it.category.lowercase() }, { it.name.lowercase() }))
                                .forEach { f ->
                                    val have = Store.installedTag(ctx, f.key)
                                    val claims = remember(f.description) { FirmwareSupport.claims(f.description) }
                                    val fit = if (claims.isEmpty || fwNumber == null) null else claims.fit(fwNumber)
                                    PayloadCard(f.name, StoreIndex.idOf(f) in fresh, f.description, dim = fit == FwFit.NOT_LISTED, tag = f.category) {
                                        val state = when {
                                            have.isEmpty() -> listOf(f.version, f.updated).filter { it.isNotEmpty() }.joinToString(" · ")
                                            have == f.version -> str(R.string.store_have, have)
                                            else -> str(R.string.store_update, have, f.version)
                                        }
                                        StateLine(state, highlight = have.isNotEmpty() && have != f.version)
                                        if (!claims.isEmpty) firmwareNote(f.name, claims, fit, firmware)?.let { (text, good) -> FirmwareLine(text, good) }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (f.download.isNotEmpty() || f.mirror.isNotEmpty()) {
                                                Button(enabled = busy.isEmpty(), onClick = { get(f.key, f.name) { Store.installFound(ctx, f) } }) {
                                                    Text(str(if (have.isNotEmpty() && have != f.version) R.string.store_get_update else R.string.store_get))
                                                }
                                            }
                                            if (f.page.isNotEmpty()) TextButton(onClick = { openLink(ctx, f.page) }) { Text(str(R.string.store_page)) }
                                        }
                                    }
                                }
                        }
                    }
                }

                // --- a GitHub search, on request ---
                SectionTitle(str(R.string.store_section_github))
                Text(str(R.string.store_github_note, type.model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val found = github
                if (found == null) {
                    OutlinedButton(enabled = !searching, onClick = {
                        searching = true
                        githubProblem = ""
                        scope.launch {
                            val known = Store.entries.map { it.repo } + community.orEmpty().map { it.repo }.filter { it.isNotEmpty() }
                            try {
                                val r = withContext(Dispatchers.IO) { StoreIndex.searchGitHub(type, known.toSet()) }
                                github = r.map { FoundRow(it, StoreEntry(it.repo, it.name, 0, type, "", aboutText = it.description)) }
                            } catch (x: Exception) {
                                githubProblem = x.message ?: x.javaClass.simpleName
                            }
                            searching = false
                        }
                    }) { Text(str(if (searching) R.string.store_github_searching else R.string.store_github_search)) }
                    if (githubProblem.isNotEmpty()) Text(str(R.string.store_problem, githubProblem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else if (found.isEmpty()) {
                    Text(str(R.string.store_github_none), style = MaterialTheme.typography.bodySmall)
                } else {
                    found.forEachIndexed { i, fr ->
                        val f = fr.found
                        val rel = fr.release
                        val asset = rel?.let { Store.pick(fr.entry, it) }
                        val have = Store.installedTag(ctx, fr.entry)
                        PayloadCard(f.repo, false, f.description, dim = false, tag = str(R.string.store_stars, f.stars, f.updated)) {
                            val state = when {
                                fr.loading -> str(R.string.store_checking)
                                fr.problem.isNotEmpty() -> str(R.string.store_problem, fr.problem)
                                rel == null -> ""
                                have == rel.tag -> str(R.string.store_have, have)
                                have.isNotEmpty() -> str(R.string.store_update, have, rel.tag)
                                else -> str(R.string.store_latest, rel.tag, rel.date)
                            }
                            if (state.isNotEmpty()) StateLine(state, highlight = false)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                when {
                                    rel != null && asset != null -> Button(enabled = busy.isEmpty(), onClick = { get(f.repo, asset.name) { Store.install(ctx, fr.entry, rel, asset) } }) {
                                        Text(str(if (have.isNotEmpty() && have != rel.tag) R.string.store_get_update else R.string.store_get))
                                    }
                                    rel != null -> Text(str(R.string.store_no_file), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                                    !fr.loading -> OutlinedButton(onClick = {
                                        github = found.toMutableList().also { it[i] = FoundRow(f, fr.entry, loading = true) }
                                        scope.launch {
                                            val done = withContext(Dispatchers.IO) {
                                                try {
                                                    FoundRow(f, fr.entry, Store.latest(fr.entry))
                                                } catch (x: Exception) {
                                                    FoundRow(f, fr.entry, problem = x.message ?: x.javaClass.simpleName)
                                                }
                                            }
                                            github = github?.toMutableList()?.also { if (i < it.size) it[i] = done }
                                        }
                                    }) { Text(str(R.string.store_github_check)) }
                                }
                                TextButton(onClick = { openLink(ctx, f.page) }) { Text(str(R.string.store_page)) }
                            }
                        }
                    }
                }
                Text(str(R.string.store_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

/** One payload: its name (with a "New" mark), what it is for, then [content] (its state and buttons). */
@Composable
private fun PayloadCard(name: String, isNew: Boolean, about: String, dim: Boolean, tag: String = "", content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dim) 0.6f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
            if (isNew) {
                Text(
                    str(R.string.store_new),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
        if (tag.isNotEmpty()) Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (about.isNotEmpty()) Text(about, style = MaterialTheme.typography.bodySmall)
        content()
    }
}

@Composable
private fun StateLine(text: String, highlight: Boolean) {
    if (text.isEmpty()) return
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FirmwareLine(text: String, good: Boolean?) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = when (good) {
            true -> statusColors().good
            false -> statusColors().bad
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/** The firmware line under a payload, and whether it is good news (true), a warning (false) or neutral (null). */
@Composable
private fun firmwareNote(e: StoreEntry, claims: FwClaims?, fit: FwFit?, firmware: String): Pair<String, Boolean?>? {
    if (e.anyFirmware) return str(R.string.store_fw_any) to null
    return firmwareNote(e.name, claims, fit, firmware)
}

@Composable
private fun firmwareNote(@Suppress("UNUSED_PARAMETER") name: String, claims: FwClaims?, fit: FwFit?, firmware: String): Pair<String, Boolean?>? {
    if (claims == null) return null
    val low = claims.lowest
    val high = claims.highest
    return when (fit) {
        FwFit.LISTED -> str(R.string.store_fw_listed, firmware) to true
        FwFit.NOT_LISTED -> str(R.string.store_fw_not_listed, firmware, low ?: "?", high ?: "?") to false
        FwFit.UNKNOWN -> str(R.string.store_fw_unknown) to null
        FwFit.ANY -> str(R.string.store_fw_any) to null
        null -> if (claims.isEmpty || low == null || high == null) str(R.string.store_fw_unknown) to null else str(R.string.store_fw_range, low, high) to null
    }
}

/**
 * Looked for once per start of the app: newer versions of payloads that came from the store, and payloads that
 * appeared in Bento's list or the community list since the store was last opened.
 */
object StoreUpdates {
    var newer by mutableStateOf<List<String>>(emptyList())
        private set
    var fresh by mutableStateOf<List<String>>(emptyList())
        private set
    private var checked = ""

    suspend fun check(ctx: Context, type: ConsoleType) {
        if (checked == type.id) return
        checked = type.id
        val (entries, comm) = withContext(Dispatchers.IO) {
            val es = StoreIndex.entries(ctx)
            es to StoreIndex.community(ctx, type, es)
        }
        Store.entries = entries
        val mine = entries.filter { it.console == type && Store.installedTag(ctx, it).isNotEmpty() }
        val fromStore = withContext(Dispatchers.IO) {
            mine.mapNotNull { e ->
                try {
                    val r = Store.latest(e)
                    if (r.tag.isNotEmpty() && r.tag != Store.installedTag(ctx, e)) "${e.name} ${r.tag}" else null
                } catch (_: Exception) {
                    null
                }
            }
        }
        val fromList = comm.orEmpty().mapNotNull { f ->
            val have = Store.installedTag(ctx, f.key)
            if (have.isNotEmpty() && f.version.isNotEmpty() && have != f.version) "${f.name} ${f.version}" else null
        }
        newer = fromStore + fromList
        if (comm != null) {
            val names = entries.filter { it.console == type }.associate { StoreIndex.idOf(it) to it.name } + comm.associate { StoreIndex.idOf(it) to it.name }
            fresh = StoreIndex.newOnes(ctx, type, names.keys).mapNotNull { names[it] }
        }
    }

    fun seen() {
        newer = emptyList()
        fresh = emptyList()
    }
}
