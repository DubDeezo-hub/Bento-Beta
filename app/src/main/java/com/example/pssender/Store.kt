package com.example.pssender // change to match your project's package name

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
    val anyFirmware: Boolean = false
)

/** A file in a release. */
data class StoreAsset(val name: String, val url: String, val size: Long)

/** The newest release of a payload. */
data class StoreRelease(val tag: String, val page: String, val date: String, val assets: List<StoreAsset>, val notes: String = "")

object Store {
    val entries = listOf(
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
                StoreAsset(name, url, (m["size"] as? Number)?.toLong() ?: -1L)
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
    fun installedTag(ctx: Context, e: StoreEntry): String = prefs(ctx).getString("store_tag_" + e.repo, "") ?: ""
    fun installedFile(ctx: Context, e: StoreEntry): String = prefs(ctx).getString("store_file_" + e.repo, "") ?: ""
    private fun remember(ctx: Context, e: StoreEntry, tag: String, file: String) =
        prefs(ctx).edit().putString("store_tag_" + e.repo, tag).putString("store_file_" + e.repo, file).apply()

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
            // An older version under another name goes, so the shelf keeps one copy
            val before = installedFile(ctx, e)
            if (before.isNotEmpty() && before != added.name) File(dir, before).delete()
            remember(ctx, e, release.tag, added.name)
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

@Composable
fun StoreDialog(type: ConsoleType, onAdded: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember(type) { Store.entries.filter { it.console == type } }
    var rows by remember(type) { mutableStateOf(list.map { StoreRow(it) }) }
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val profileId = ActiveConsole.id
    var firmware by remember(profileId) { mutableStateOf(ConsoleFirmware.get(ctx, profileId)) }
    var editingFw by remember { mutableStateOf(false) }
    var fwText by remember { mutableStateOf("") }
    LaunchedEffect(type) {
        list.forEachIndexed { i, e ->
            val row = withContext(Dispatchers.IO) {
                try {
                    val r = Store.latest(e)
                    StoreRow(e, r, loading = false, claims = if (e.anyFirmware) null else Store.firmwareClaims(ctx, e, r))
                } catch (x: Exception) {
                    StoreRow(e, problem = x.message ?: x.javaClass.simpleName, loading = false)
                }
            }
            rows = rows.toMutableList().also { it[i] = row }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { TipTitle(str(R.string.store_title, type.model), Tip.STORE) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(str(R.string.store_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                rows.forEach { row ->
                    val e = row.entry
                    val rel = row.release
                    val have = Store.installedTag(ctx, e)
                    val asset = rel?.let { Store.pick(e, it) }
                    val fwNumber = ConsoleFirmware.number(firmware)
                    val fit = when {
                        e.anyFirmware -> FwFit.ANY
                        row.claims == null -> null
                        fwNumber == null -> null
                        else -> row.claims.fit(fwNumber)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (fit == FwFit.NOT_LISTED) 0.6f else 1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(e.name, style = MaterialTheme.typography.titleSmall)
                        Text(str(e.about), style = MaterialTheme.typography.bodySmall)
                        val state = when {
                            row.loading -> str(R.string.store_checking)
                            row.problem.isNotEmpty() -> str(R.string.store_problem, row.problem)
                            rel == null -> ""
                            have.isEmpty() -> str(R.string.store_latest, rel.tag, rel.date)
                            have == rel.tag -> str(R.string.store_have, have)
                            else -> str(R.string.store_update, have, rel.tag)
                        }
                        Text(
                            state,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rel != null && have.isNotEmpty() && have != rel.tag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        firmwareNote(e, row.claims, fit, firmware)?.let { (text, good) ->
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (rel != null && asset != null) {
                                Button(enabled = busy.isEmpty(), onClick = {
                                    busy = e.repo
                                    message = AppText.get(R.string.store_downloading, asset.name)
                                    scope.launch {
                                        message = withContext(Dispatchers.IO) {
                                            try {
                                                Store.install(ctx, e, rel, asset)
                                            } catch (x: ShelfTooLarge) {
                                                shelfTooLargeMessage(x.limit)
                                            } catch (x: Exception) {
                                                failedText(x)
                                            }
                                        }
                                        busy = ""
                                        onAdded()
                                    }
                                }) { Text(str(if (have.isNotEmpty() && have != rel.tag) R.string.store_get_update else R.string.store_get)) }
                            } else if (rel != null) {
                                Text(str(R.string.store_no_file), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                            }
                            TextButton(onClick = { openLink(ctx, rel?.page?.ifEmpty { null } ?: "https://github.com/${e.repo}/releases") }) {
                                Text(str(R.string.store_page))
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

/** The firmware line under a payload, and whether it is good news (true), a warning (false) or neutral (null). */
@Composable
private fun firmwareNote(e: StoreEntry, claims: FwClaims?, fit: FwFit?, firmware: String): Pair<String, Boolean?>? {
    if (e.anyFirmware) return str(R.string.store_fw_any) to null
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

/** Newer versions of payloads that came from the store, looked for once per start of the app. */
object StoreUpdates {
    var newer by mutableStateOf<List<String>>(emptyList())
        private set
    private var checked = ""

    suspend fun check(ctx: Context, type: ConsoleType) {
        if (checked == type.id) return
        checked = type.id
        val mine = Store.entries.filter { it.console == type && Store.installedTag(ctx, it).isNotEmpty() }
        if (mine.isEmpty()) return
        newer = withContext(Dispatchers.IO) {
            mine.mapNotNull { e ->
                try {
                    val r = Store.latest(e)
                    if (r.tag.isNotEmpty() && r.tag != Store.installedTag(ctx, e)) "${e.name} ${r.tag}" else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun seen() {
        newer = emptyList()
    }
}
