package com.example.pssender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Editing small files on the console from the phone: etaHEN's settings file as switches, and any small text file as
// text. Both read the file over FTP, and saving keeps the old file as "<name>.bak" first.
// ---------------------------------------------------------------------------------------------

/** One of etaHEN's settings: its key in config.ini, what it does, and how it is set. */
class EtaHenSetting(val key: String, val label: Int, val group: Int, val choices: List<Int> = emptyList(), val number: Boolean = false)

object EtaHenConfig {
    const val PATH = "/data/etaHEN/config.ini"

    private val SERVICES = R.string.etahen_group_services
    private val TOOLBOX = R.string.etahen_group_toolbox
    private val REST = R.string.etahen_group_rest
    private val OVERLAY = R.string.etahen_group_overlay

    /** The settings etaHEN's README describes (others in the file are shown as plain values). */
    val KNOWN = listOf(
        EtaHenSetting("FTP", R.string.etahen_ftp, SERVICES),
        EtaHenSetting("DPI", R.string.etahen_dpi, SERVICES),
        EtaHenSetting("DPI_v2", R.string.etahen_dpi_v2, SERVICES),
        EtaHenSetting("Klog", R.string.etahen_klog, SERVICES),
        EtaHenSetting("PS5Debug", R.string.etahen_ps5debug, SERVICES),
        EtaHenSetting("ALLOW_FTP_DEV_ACCESS", R.string.etahen_ftp_dev, SERVICES),
        EtaHenSetting("discord_rpc", R.string.etahen_discord, SERVICES),
        EtaHenSetting("Allow_data_in_sandbox", R.string.etahen_sandbox, SERVICES),
        EtaHenSetting("toolbox_auto_start", R.string.etahen_toolbox_auto, TOOLBOX),
        EtaHenSetting(
            "StartOption", R.string.etahen_start, TOOLBOX,
            listOf(R.string.etahen_start_none, R.string.etahen_start_home, R.string.etahen_start_settings, R.string.etahen_start_toolbox, R.string.etahen_start_itemzflow)
        ),
        EtaHenSetting("Display_tids", R.string.etahen_tids, TOOLBOX),
        EtaHenSetting("etaHEN_Game_Options", R.string.etahen_game_options, TOOLBOX),
        EtaHenSetting("libhijacker_cheats", R.string.etahen_cheats, TOOLBOX),
        EtaHenSetting("launch_itemzflow", R.string.etahen_itemzflow, TOOLBOX),
        EtaHenSetting("auto_eject_disc", R.string.etahen_eject, TOOLBOX),
        EtaHenSetting("testkit", R.string.etahen_testkit, TOOLBOX),
        EtaHenSetting("APP_JB_Debug_Msg", R.string.etahen_jb_debug, TOOLBOX),
        EtaHenSetting("Rest_Mode_Delay_Seconds", R.string.etahen_rest_delay, REST, number = true),
        EtaHenSetting("Util_rest_kill", R.string.etahen_util_rest, REST),
        EtaHenSetting("Game_rest_kill", R.string.etahen_game_rest, REST),
        EtaHenSetting("disable_toolbox_auto_start_for_rest_mode", R.string.etahen_rest_toolbox, REST),
        EtaHenSetting("overlay_ram", R.string.etahen_overlay_ram, OVERLAY),
        EtaHenSetting("overlay_cpu", R.string.etahen_overlay_cpu, OVERLAY),
        EtaHenSetting("overlay_gpu", R.string.etahen_overlay_gpu, OVERLAY),
        EtaHenSetting("overlay_ip", R.string.etahen_overlay_ip, OVERLAY),
        EtaHenSetting("overlay_kstuff", R.string.etahen_overlay_kstuff, OVERLAY)
    )

    /** The "key=value" lines of an INI file, in order (section headers and comments left out). */
    fun parse(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("[") || line.startsWith(";") || line.startsWith("#") || !line.contains('=')) continue
            out[line.substringBefore('=').trim()] = line.substringAfter('=').trim()
        }
        return out
    }

    /**
     * [text] with [changes] applied: each changed key's line gets its new value (everything else, comments and order
     * included, stays as it was); keys the file doesn't have yet are added at its end.
     */
    fun apply(text: String, changes: Map<String, String>): String {
        val left = LinkedHashMap(changes)
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(eol).map { raw ->
            val line = raw.trim()
            if (line.startsWith("[") || line.startsWith(";") || line.startsWith("#") || !line.contains('=')) return@map raw
            val key = line.substringBefore('=').trim()
            val v = left.remove(key) ?: return@map raw
            raw.substringBefore('=') + "=" + (if (raw.substringAfter('=').startsWith(" ")) " " else "") + v
        }.toMutableList()
        if (left.isNotEmpty()) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            left.forEach { (k, v) -> lines.add("$k=$v") }
            lines.add("")
        }
        return lines.joinToString(eol)
    }
}

/** Whether [name] looks like a text file that can be edited here (by its ending). */
fun isEditableText(name: String): Boolean =
    Regex("""\.(txt|ini|cfg|conf|json|xml|log|md|lua|sh|csv|yml|yaml|toml|properties|list)$""", RegexOption.IGNORE_CASE).containsMatchIn(name)

/** Files larger than this aren't opened for editing. */
const val TEXT_EDIT_MAX = 256 * 1024

/** etaHEN's settings, read from the console and saved back over FTP. */
@Composable
fun EtaHenSettingsDialog(settings: FtpSettings, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var changed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(settings) {
        val bytes = withContext(Dispatchers.IO) { try { ftpReadSmall(settings, EtaHenConfig.PATH, TEXT_EDIT_MAX) } catch (e: Exception) { message = failedText(e); null } }
        if (bytes != null) {
            val t = String(bytes, Charsets.UTF_8)
            text = t
            values = EtaHenConfig.parse(t)
        } else if (message.isEmpty()) message = AppText.get(R.string.etahen_missing, EtaHenConfig.PATH)
        busy = false
    }
    val now = values + changed
    AlertDialog(
        onDismissRequest = onClose,
        title = { TipTitle(str(R.string.etahen_title), Tip.ETAHEN_SETTINGS, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (message.isNotEmpty()) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    HelpLink(message)
                }
                if (text != null) {
                    Text(str(R.string.etahen_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EtaHenConfig.KNOWN.groupBy { it.group }.forEach { (group, list) ->
                        Text(str(group), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        list.forEach { s ->
                            val v = now[s.key]
                            when {
                                s.choices.isNotEmpty() -> {
                                    Text(str(s.label), style = MaterialTheme.typography.bodyMedium)
                                    ChipRow {
                                        s.choices.forEachIndexed { i, c ->
                                            Chip(str(c), (v?.toIntOrNull() ?: 0) == i) { changed = changed + (s.key to i.toString()) }
                                        }
                                    }
                                }
                                s.number -> Field(str(s.label), v ?: "0") { t -> changed = changed + (s.key to t.filter { it.isDigit() }.take(5)) }
                                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(str(s.label), style = MaterialTheme.typography.bodyMedium)
                                        Text(s.key + if (v == null) " · " + str(R.string.etahen_not_in_file) else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = v == "1", onCheckedChange = { on -> changed = changed + (s.key to if (on) "1" else "0") })
                                }
                            }
                        }
                    }
                    // Everything else in the file, as plain values
                    val known = EtaHenConfig.KNOWN.map { it.key }.toSet()
                    val others = now.keys.filter { it !in known }
                    if (others.isNotEmpty()) {
                        Text(str(R.string.etahen_group_other), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        others.forEach { k -> Field(k, now[k].orEmpty()) { t -> changed = changed + (k to t.take(64)) } }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text != null && changed.any { (k, v) -> values[k] != v }, onClick = {
                val t = text ?: return@TextButton
                val real = changed.filter { (k, v) -> values[k] != v }
                busy = true
                scope.launch {
                    val updated = EtaHenConfig.apply(t, real)
                    message = withContext(Dispatchers.IO) {
                        try {
                            ftpWriteSmall(settings, EtaHenConfig.PATH, updated.toByteArray(Charsets.UTF_8))
                            AppText.get(R.string.etahen_saved)
                        } catch (e: Exception) {
                            failedText(e)
                        }
                    }
                    if (message == AppText.get(R.string.etahen_saved)) {
                        text = updated
                        values = EtaHenConfig.parse(updated)
                        changed = emptyMap()
                    }
                    busy = false
                }
            }) { Text(str(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

/** A small text file on the console, opened for editing. */
@Composable
fun TextFileDialog(settings: FtpSettings, path: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(path) {
        val bytes = withContext(Dispatchers.IO) { try { ftpReadSmall(settings, path, TEXT_EDIT_MAX) } catch (e: Exception) { message = failedText(e); null } }
        when {
            bytes == null && message.isEmpty() -> message = AppText.get(R.string.text_edit_too_big, fmtBytes(TEXT_EDIT_MAX.toLong()))
            bytes != null && bytes.take(4096).any { it.toInt() == 0 } -> message = AppText.get(R.string.text_edit_binary)
            bytes != null -> {
                original = String(bytes, Charsets.UTF_8)
                text = original.orEmpty()
            }
        }
        busy = false
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (message.isNotEmpty()) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    HelpLink(message)
                }
                if (original != null) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp)
                    )
                    Text(str(R.string.text_edit_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && original != null && text != original, onClick = {
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            ftpWriteSmall(settings, path, text.toByteArray(Charsets.UTF_8))
                            null
                        } catch (e: Exception) {
                            failedText(e)
                        }
                    }
                    message = ok ?: AppText.get(R.string.text_edit_saved)
                    if (ok == null) original = text
                    busy = false
                }
            }) { Text(str(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}
