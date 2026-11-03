package com.example.pssender

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

    /** The counts read already (none when not read yet), without asking GitHub: for checks that can't wait. */
    fun peek(): Map<String, Map<String, CompatCounts>> = cached.orEmpty()

    /** For the tests. */
    internal fun use(data: Map<String, Map<String, CompatCounts>>?) {
        cached = data
    }
}

/**
 * The warning before sending [names] when others on the same [model] and [firmware] mostly saw it crash (at least two
 * crashes, and more crashes than successes); null when nothing points that way.
 */
fun compatWarning(names: List<String>, model: String, firmware: String?, data: Map<String, Map<String, CompatCounts>>): String? {
    if (firmware.isNullOrBlank()) return null
    for (name in names) {
        val c = data[compatKey(name)]?.get(compatWhere(model, firmware)) ?: continue
        if (c.crashed >= 2 && c.crashed > c.worked) {
            return AppText.get(R.string.compat_warn, name, model, firmware, c.crashed, c.worked)
        }
    }
    return null
}

/**
 * The counts to show for one payload, from [byWhere] ("PS5 10.40" -> counts): this console's model and firmware first,
 * then the same model's other firmware (newest first), then the other model's. Empty counts are left out.
 */
fun compatRows(byWhere: Map<String, CompatCounts>, model: String, firmware: String): List<Pair<String, CompatCounts>> {
    val here = compatWhere(model, firmware)
    fun fwNumber(where: String) = ConsoleFirmware.number(where.substringAfter(' ')) ?: 0
    return byWhere.entries.filter { it.value.total > 0 }.sortedWith(
        compareByDescending<Map.Entry<String, CompatCounts>> { it.key == here }
            .thenByDescending { it.key.startsWith("$model ") }
            .thenByDescending { fwNumber(it.key) }
    ).map { it.key to it.value }
}

/** At most this many firmware lines are shown for a payload; the rest are counted. */
const val COMPAT_SHOWN = 6

/** In a payload's details: what others saw on every model and firmware (this console's first), and "Say how it went". */
@Composable
fun CompatSection(name: String, version: String) {
    val ctx = LocalContext.current
    val model = ActiveConsole.type.model
    val firmware = remember(ActiveConsole.id) { ConsoleFirmware.get(ctx, ActiveConsole.id) }
    var rows by remember(name) { mutableStateOf<List<Pair<String, CompatCounts>>>(emptyList()) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(name, firmware) {
        rows = withContext(Dispatchers.IO) { compatRows(CompatData.get(ctx)[compatKey(name)].orEmpty(), model, firmware) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(str(R.string.compat_title), style = MaterialTheme.typography.labelLarge)
        if (rows.isEmpty()) Text(str(R.string.compat_none_any), style = MaterialTheme.typography.bodySmall)
        rows.take(COMPAT_SHOWN).forEach { (where, c) ->
            val fw = where.substringAfter(' ')
            val m = where.substringBefore(' ')
            Text(
                str(R.string.compat_counts, m, fw, c.worked, c.crashed, c.failed),
                style = MaterialTheme.typography.bodySmall,
                color = if (where == compatWhere(model, firmware)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        if (rows.size > COMPAT_SHOWN) {
            Text(str(R.string.compat_more, rows.size - COMPAT_SHOWN), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { asking = true }) { Text(str(R.string.compat_report)) }
    }
    if (asking) CompatReportDialog(name, version, null) { asking = false }
}

/**
 * Asks how [name] went and on which console model and firmware (any of them; this console's are filled in when known),
 * then opens the filled-in report on GitHub. [preset] picks a result to start with (the crash diary picks "crashed").
 */
@Composable
fun CompatReportDialog(name: String, version: String, preset: CompatResult?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var model by remember { mutableStateOf(ActiveConsole.type.model) }
    var firmware by remember { mutableStateOf(ConsoleFirmware.get(ctx, ActiveConsole.id)) }
    var result by remember { mutableStateOf(preset) }
    val fw = ConsoleFirmware.normalize(firmware)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.compat_report)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                ChipRow {
                    ConsoleType.entries.forEach { t -> Chip(t.model, model == t.model) { model = t.model } }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = firmware,
                    onValueChange = { v -> firmware = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                    singleLine = true,
                    label = { Text(str(R.string.compat_fw_label)) },
                    supportingText = { Text(str(if (fw == null && firmware.isNotBlank()) R.string.compat_bad_fw else R.string.compat_fw_any)) },
                    isError = fw == null && firmware.isNotBlank()
                )
                ChipRow {
                    CompatResult.entries.forEach { r -> Chip(str(r.res), result == r) { result = r } }
                }
                Text(str(R.string.compat_report_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = fw != null && result != null, onClick = {
                val r = result
                if (fw != null && r != null) {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(compatReportUrl(name, version, model, fw, r))))
                    } catch (_: Exception) {
                    }
                }
                onClose()
            }) { Text(str(R.string.compat_send)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}
