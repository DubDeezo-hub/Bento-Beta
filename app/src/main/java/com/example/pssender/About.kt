package com.example.pssender // change to match your project's package name

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// The About window: version, updates, backup and restore, and a report to paste into a bug report.
// ---------------------------------------------------------------------------------------------

/** Where the builds are published. */
const val RELEASES_URL = "https://github.com/$UPDATE_REPO/releases"

/** The longest piece of the last message that goes into a report. */
const val REPORT_MESSAGE_MAX = 600

private val LINK_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val IPV4_IN_TEXT = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

/** Takes out of [text] what a bug report shouldn't carry: web links and IP addresses. */
fun scrubForReport(text: String): String =
    text.replace(LINK_IN_TEXT, "(link)").replace(IPV4_IN_TEXT, "x.x.x.x")

/**
 * The text of the report: the app, the phone, how many consoles are set up and the last message that was shown.
 * It is for the person who reads the bug report, so it is always in English.
 */
fun buildReport(version: AppVersion, phone: String, consoleCount: Int, lastMessage: String, extras: ReportExtras = ReportExtras()): String {
    val lines = ArrayList<String>()
    lines.add("Bento ${version.name} (${version.code})")
    lines.add(phone)
    if (extras.language.isNotEmpty()) lines.add("App language: " + extras.language)
    lines.add("Consoles: $consoleCount")
    if (extras.console.isNotEmpty()) lines.add("Console: " + extras.console + if (extras.firmware.isNotEmpty()) ", firmware " + extras.firmware else "")
    if (extras.debugger.isNotEmpty()) lines.add("Debugger: " + scrubForReport(extras.debugger))
    if (extras.payloads.isNotEmpty()) lines.add("From the store: " + extras.payloads.joinToString(", "))
    if (extras.check.isNotEmpty()) {
        lines.add("Last connection check:")
        extras.check.forEach { lines.add("  " + scrubForReport(it)) }
    }
    val message = scrubForReport(lastMessage).trim()
    if (message.isNotEmpty()) lines.add("Last message: " + message.take(REPORT_MESSAGE_MAX))
    val recent = extras.recent.map { scrubForReport(it).trim().take(REPORT_MESSAGE_MAX) }.filter { it.isNotEmpty() && it != message.take(REPORT_MESSAGE_MAX) }
    if (recent.isNotEmpty()) {
        lines.add("Recent problems:")
        recent.forEach { lines.add("  - $it") }
    }
    return lines.joinToString("\n")
}

/** What else goes into the debug info: the console, its firmware, the debugger, the store's payloads, recent problems. */
class ReportExtras(
    val language: String = "",
    val console: String = "",
    val firmware: String = "",
    val debugger: String = "",
    val payloads: List<String> = emptyList(),
    val check: List<String> = emptyList(),
    val recent: List<String> = emptyList()
)

/** The debug info as it stands now, to paste into a bug report or a forum post. */
object DebugInfo {
    /** How many recent problem messages go in. */
    private const val RECENT = 5

    fun extras(ctx: Context): ReportExtras {
        val type = ActiveConsole.type
        val snap = MemorySession.snapshot
        return ReportExtras(
            language = AppLanguage.current(ctx).ifEmpty { "phone's" },
            console = type.model,
            firmware = ConsoleFirmware.get(ctx, ActiveConsole.id),
            debugger = snap?.info?.let { i -> listOf(i.brand, i.protocol.ifBlank { null }?.let { "protocol $it" }, i.firmware.ifBlank { null }?.let { "firmware $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(", ") } ?: "",
            payloads = Store.entries.filter { it.console == type }.mapNotNull { e -> Store.installedTag(ctx, e).ifEmpty { null }?.let { "${e.name} $it" } },
            check = Diagnose.lastSummary,
            recent = Hints.recent(RECENT)
        )
    }

    fun build(ctx: Context, lastMessage: String = ""): String =
        buildReport(appVersion(ctx), phoneSummary(), ProfileStore.load(ctx).size, lastMessage, extras(ctx))
}

/**
 * The About window. [lastMessage] is the message the screen showed last (it goes into the report), [onUpdateFound] is
 * told when the "Check now" button finds a newer version.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutDialog(
    version: AppVersion,
    consoleCount: Int,
    lastMessage: String,
    onUpdateFound: (ReleaseInfo) -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf<BackupData?>(null) }
    var showLimit by remember { mutableStateOf(false) }
    val speedLimit by TransferState.limit.collectAsState()

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch { status = withContext(Dispatchers.IO) { writeBackupTo(ctx, uri, version.name) } }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val pick = withContext(Dispatchers.IO) { pickBackup(ctx, uri) }
                val data = pick.data
                if (data != null) restoring = data else status = pick.message
            }
        }
    }

    restoring?.let { data ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(str(R.string.restore_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(str(R.string.backup_contents, data.consoles.size, data.bookmarks?.size ?: 0, data.shelf.size))
                    backupDay(data)?.let { Text(str(R.string.backup_made, it), style = MaterialTheme.typography.bodySmall) }
                    Text(str(R.string.restore_text))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) { applyBackup(ctx, data) }
                        Toast.makeText(ctx, restoreMessage(failed), Toast.LENGTH_LONG).show()
                        // The screen reads its settings when it starts, so it starts again to show the restored ones
                        findActivity(ctx)?.recreate()
                    }
                }) { Text(str(R.string.restore_button)) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(str(R.string.action_cancel)) } }
        )
    }

    if (showLimit) SpeedLimitDialog(onClose = { showLimit = false })

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.about_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(str(R.string.about_version, version.name), style = MaterialTheme.typography.titleMedium)
                Text(
                    str(R.string.credit_line, str(R.string.credit_name)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                UpdateSection(version, onUpdateFound)

                Text(str(R.string.speed_limit_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Text(str(R.string.speed_limit_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showLimit = true }) {
                    Text(if (speedLimit > 0L) fmtRate(speedLimit) else str(R.string.speed_limit_off))
                }

                Text(str(R.string.backup_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Text(str(R.string.backup_text), style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(onClick = { saveFile.launch(backupFileName()) }) { Text(str(R.string.backup_save)) }
                    Button(onClick = { openFile.launch(arrayOf("*/*")) }) { Text(str(R.string.backup_restore)) }
                }
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    HelpLink(status)
                }

                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = {
                        report = buildReport(version, phoneSummary(), consoleCount, lastMessage, DebugInfo.extras(ctx))
                        copyText(ctx, "Bento", report, AppText.get(R.string.about_report_copied))
                    }) { Text(str(R.string.about_report)) }
                    TextButton(onClick = {
                        report = buildReport(version, phoneSummary(), consoleCount, lastMessage, DebugInfo.extras(ctx))
                        shareText(ctx, report, AppText.get(R.string.about_report_share))
                    }) { Text(str(R.string.about_report_share)) }
                    TextButton(onClick = { openLink(ctx, RELEASES_URL) }) { Text(str(R.string.about_releases)) }
                    TextButton(onClick = {
                        onClose()
                        WelcomeState.showing = true
                    }) { Text(str(R.string.welcome_open)) }
                }
                Text(
                    str(R.string.about_report_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (report.isNotEmpty()) {
                    Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}
