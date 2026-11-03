package com.example.pssender

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// The shared compatibility list: anyone can say how a payload went on their console ("worked", "crashed", "didn't
// work"). The app fills in a GitHub issue with only the payload's name, its version, the console model, the firmware and
// the result; the person sends it themselves (with their GitHub account), so nothing leaves the phone without them. A
// workflow in the repository counts it into compat.json on the "compat-data" branch, and the app shows the counts next
// to each payload. It goes only to GitHub, never to Sony.
// ---------------------------------------------------------------------------------------------

/** How a payload went. [wire] is the word in the report. */
enum class CompatResult(val wire: String, val res: Int) {
    WORKED("worked", R.string.compat_worked),
    CRASHED("crashed", R.string.compat_crashed),
    FAILED("failed", R.string.compat_failed)
}

/** The counts for one payload on one model and firmware. */
data class CompatCounts(val worked: Int, val crashed: Int, val failed: Int) {
    val total: Int get() = worked + crashed + failed
}

/**
 * The name reports are counted under: lower case, without the file ending and a version at the end, so "etaHEN-2.5B.bin"
 * and "etaHEN.bin" count together. Must match the workflow (.github/workflows/compat.yml).
 */
fun compatKey(name: String): String {
    val lower = name.trim().lowercase()
    val noEnd = lower.replace(Regex("""\.(elf|bin|js|lua|jar)$"""), "")
    val noVersion = noEnd.replace(Regex("""[-_ ]*v?\d+(\.\d+)*[a-z]?$"""), "").trim()
    return noVersion.ifEmpty { lower }
}

/** "PS5 10.40": where a count applies. */
fun compatWhere(model: String, firmware: String): String = "$model $firmware"

/** Reads compat.json: payload key -> (model and firmware -> counts). Broken input gives an empty map. */
fun parseCompat(json: String?): Map<String, Map<String, CompatCounts>> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val reports = JSONObject(json).optJSONObject("reports") ?: return emptyMap()
        val out = HashMap<String, Map<String, CompatCounts>>()
        for (key in reports.keys()) {
            val byWhere = reports.optJSONObject(key) ?: continue
            val m = HashMap<String, CompatCounts>()
            for (where in byWhere.keys()) {
                val c = byWhere.optJSONObject(where) ?: continue
                m[where] = CompatCounts(c.optInt("worked").coerceAtLeast(0), c.optInt("crashed").coerceAtLeast(0), c.optInt("failed").coerceAtLeast(0))
            }
            out[key] = m
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}

/** The text of a report, as the workflow reads it (one field per line, after the marker). */
fun compatReportBody(payload: String, version: String, model: String, firmware: String, result: CompatResult): String = buildString {
    append("<!-- crimson-compat -->\n")
    append("payload: ").append(payload.filter { it.isLetterOrDigit() || it in "._ +-" }.take(64)).append('\n')
    if (version.isNotBlank()) append("version: ").append(version.filter { it.isLetterOrDigit() || it in "._+-" }.take(32)).append('\n')
    append("console: ").append(model).append('\n')
    append("firmware: ").append(firmware).append('\n')
    append("result: ").append(result.wire).append('\n')
    append("\nSent from Crimson Multitool. Only the lines above are counted; the issue is closed by itself.\n")
}

/** The address that opens the filled-in report on GitHub. */
fun compatReportUrl(payload: String, version: String, model: String, firmware: String, result: CompatResult): String {
    val title = "Compatibility: $payload on $model $firmware (${result.wire})"
    return "https://github.com/$UPDATE_REPO/issues/new?title=" + Uri.encode(title) +
        "&body=" + Uri.encode(compatReportBody(payload, version, model, firmware, result))
}

object CompatData {
    private const val PREFS = "compat"
    private const val KEEP_MS = 24L * 60 * 60 * 1000
    private val URL = "https://raw.githubusercontent.com/$UPDATE_REPO/compat-data/compat.json"

    @Volatile
    private var cached: Map<String, Map<String, CompatCounts>>? = null

    /** The counts, read from GitHub at most once a day (the copy kept on the phone in between). Blocking. */
    fun get(ctx: Context): Map<String, Map<String, CompatCounts>> {
        cached?.let { return it }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        val kept = p.getString("json", null)
        val now = System.currentTimeMillis()
        val json = if (kept != null && now - at in 0..KEEP_MS) kept else try {
            val r = webGet(URL, true, "application/json")
            when (r.code) {
                200 -> r.body.also { p.edit().putString("json", it).putLong("at", now).apply() }
                404 -> "".also { p.edit().putString("json", it).putLong("at", now).apply() } // no reports yet
                else -> kept
            }
        } catch (_: Exception) {
            kept
        }
        return parseCompat(json).also { cached = it }
    }
}

/** In a payload's details: what others saw on this model and firmware, and buttons to say how it went here. */
@Composable
fun CompatSection(name: String, version: String) {
    val ctx = LocalContext.current
    val model = ActiveConsole.type.model
    val firmware = remember(ActiveConsole.id) { ConsoleFirmware.get(ctx, ActiveConsole.id) }
    var counts by remember(name) { mutableStateOf<CompatCounts?>(null) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(name, firmware) {
        if (firmware.isNotEmpty()) {
            counts = withContext(Dispatchers.IO) { CompatData.get(ctx)[compatKey(name)]?.get(compatWhere(model, firmware)) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(str(R.string.compat_title), style = MaterialTheme.typography.labelLarge)
        if (firmware.isEmpty()) {
            Text(str(R.string.compat_need_fw, model), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val c = counts
        Text(
            if (c == null || c.total == 0) str(R.string.compat_none, model, firmware)
            else str(R.string.compat_counts, model, firmware, c.worked, c.crashed, c.failed),
            style = MaterialTheme.typography.bodySmall
        )
        if (!asking) {
            OutlinedButton(onClick = { asking = true }) { Text(str(R.string.compat_report)) }
        } else {
            Text(str(R.string.compat_report_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                CompatResult.entries.forEach { r ->
                    TextButton(onClick = {
                        asking = false
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(compatReportUrl(name, version, model, firmware, r))))
                        } catch (_: Exception) {
                        }
                    }) { Text(str(r.res)) }
                }
            }
        }
    }
}
