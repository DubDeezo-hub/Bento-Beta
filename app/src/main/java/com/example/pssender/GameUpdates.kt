package com.example.pssender

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Game updates: which installed games have a newer patch than the one on the console. The lists come from the community
// patch sites Orbis Patches (PS4 titles) and Prospero Patches (PS5 titles), the same ones PatchDL uses. Bento sends
// them a title ID and nothing else. Sony's servers are never contacted, by the phone or by the console, and nothing is
// downloaded or installed.
// ---------------------------------------------------------------------------------------------

/** The newest patch a site lists for a title, and the newest one this console's firmware can take. */
data class TitleUpdate(val version: String, val needsFirmware: String = "", val size: Long = -1, val newestForFirmware: String = "")

/** What the check found for one title. */
sealed class UpdateState {
    /** The newest patch is newer than the installed version. */
    class Available(val update: TitleUpdate, val installed: String) : UpdateState()

    /** The installed version is the newest. */
    class UpToDate(val version: String) : UpdateState()

    /** The site lists no patch for it (or couldn't be read). */
    object Unknown : UpdateState()
}

/** One patch in a site's list. [firmware] is like "10.20" (a PS5 title's requirement) or "" when it isn't one. */
data class SitePatch(val version: String, val firmware: String, val size: Long)

object GameUpdates {
    /** Titles looked up at the same time. */
    private const val PARALLEL = 2

    var results by mutableStateOf<Map<String, UpdateState>>(emptyMap())
        private set
    var checking by mutableStateOf(false)
        private set
    var done by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set

    /** The console the results belong to ("host:port"). */
    private var checkedFor = ""

    fun forget() {
        results = emptyMap()
        checkedFor = ""
    }

    /** Drops the results when they belong to another console than [key] ("host:port"). */
    fun keepOnly(key: String) {
        if (checkedFor.isNotEmpty() && key != checkedFor) forget()
    }

    /** The site that lists [titleId]'s patches. */
    fun siteFor(titleId: String): String = if (titleId.startsWith("CUSA")) "https://orbispatches.com" else "https://prosperopatches.com"

    /** The key a title page hands its own list request (Prospero: data-key="…"; Orbis: 'key': '…'). */
    fun keyIn(html: String): String? =
        Regex("""data-key="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""'key':\s*'([0-9a-fA-F]+)'""").find(html)?.groupValues?.get(1)

    /** "10.20.00.06" (as Prospero writes it) → "10.20"; anything else (a PS4 title's "9.00") as it is. */
    private fun firmwareOf(text: String): String {
        val parts = text.trim().split('.')
        return if (parts.size >= 3) parts[0].trimStart('0').ifEmpty { "0" } + "." + parts[1].padStart(2, '0').take(2) else text.trim()
    }

    /** The patches in a site's reply: every record that has "is_latest", with its version, firmware and size. */
    fun parsePatches(json: String): List<SitePatch> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        if (root["success"] != true) return emptyList()
        val out = ArrayList<SitePatch>()
        fun walk(x: Any?) {
            when (x) {
                is Map<*, *> -> {
                    if (x.containsKey("is_latest")) {
                        val v = ((x["content_ver"] as? String) ?: (x["version"] as? String)).orEmpty().trim()
                        if (v.isNotEmpty()) {
                            val size = when (val f = x["filesize"]) { is Number -> f.toLong(); is String -> f.toLongOrNull() ?: -1; else -> -1 }
                            out.add(SitePatch(v, firmwareOf((x["required_firmware"] as? String).orEmpty()), size))
                        }
                    } else x.values.forEach { walk(it) }
                }
                is List<*> -> x.forEach { walk(it) }
            }
        }
        walk(root)
        return out
    }

    /** The patches the site lists for [titleId]. Blocking; null when the site can't be read. */
    fun sitePatches(titleId: String): List<SitePatch>? {
        val base = siteFor(titleId)
        return try {
            val page = webGet("$base/$titleId", true, "text/html", 12_000)
            if (page.code == 404) return emptyList()
            if (page.code != 200) return null
            val key = keyIn(page.body) ?: return null
            val reply = post("$base/api/internal/loadpatches", "{\"titleid\":\"$titleId\",\"key\":\"$key\"}") ?: return null
            parsePatches(reply)
        } catch (_: Exception) {
            null
        }
    }

    /** A POST the way the site's own page sends it (JSON in a form content type). */
    private fun post(url: String, body: String): String? {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 12_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Bento)")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) return null
            return c.inputStream.use { readLimited(it, 1_000_000) }
        } finally {
            c.disconnect()
        }
    }

    /** The newest of [patches], and the newest one [consoleFirmware] can take (only PS5 titles name firmware). */
    fun newest(patches: List<SitePatch>, consoleFirmware: String): TitleUpdate? {
        val top = patches.maxWithOrNull { a, b -> PkgCheck.compareVersions(a.version, b.version) } ?: return null
        val fw = ConsoleFirmware.number(consoleFirmware)
        val fits = if (fw == null) null else patches.filter { p ->
            val need = ConsoleFirmware.number(p.firmware)
            need == null || need <= fw
        }.maxWithOrNull { a, b -> PkgCheck.compareVersions(a.version, b.version) }
        return TitleUpdate(top.version, top.firmware, top.size, fits?.version.orEmpty())
    }

    /**
     * Looks up every title in [apps] ([patchVersions] are the versions of installed PS4 patches, which are newer than
     * the game's own). Results arrive one by one in [results].
     */
    suspend fun check(key: String, apps: List<AppEntry>, patchVersions: Map<String, String>, consoleFirmware: String = "", consoleIsPs5: Boolean = true) {
        if (checking) return
        if (key != checkedFor) results = emptyMap()
        checkedFor = key
        checking = true
        done = 0
        total = apps.size
        val gate = Semaphore(PARALLEL)
        try {
            coroutineScope {
                apps.map { a ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val installed = listOf(a.info?.version.orEmpty(), patchVersions[a.id].orEmpty())
                                .filter { it.isNotEmpty() }.maxWithOrNull { x, y -> PkgCheck.compareVersions(x, y) }.orEmpty()
                            // A PS4 title's firmware is a PS4 firmware: it says nothing about what a PS5 can take
                            val sameSystem = a.id.startsWith("CUSA") != consoleIsPs5
                            val upd = sitePatches(a.id)?.let { list ->
                                newest(if (sameSystem) list else list.map { it.copy(firmware = "") }, if (sameSystem) consoleFirmware else "")
                            }
                            val state = when {
                                upd == null -> UpdateState.Unknown
                                installed.isNotEmpty() && PkgCheck.compareVersions(upd.version, installed) > 0 -> UpdateState.Available(upd, installed)
                                installed.isEmpty() -> UpdateState.Unknown
                                else -> UpdateState.UpToDate(installed)
                            }
                            withContext(Dispatchers.Main) {
                                if (checkedFor == key) results = results + (a.id to state)
                                done++
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            checking = false
        }
    }

    val available: Int get() = results.values.count { it is UpdateState.Available }
}
