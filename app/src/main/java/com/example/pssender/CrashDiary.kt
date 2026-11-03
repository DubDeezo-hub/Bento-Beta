package com.example.pssender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// When the console log stops answering (a likely kernel panic), Crimson Multitool notes what it can: the time, the last log lines,
// and which payloads went out in the minutes before. The notes are kept on the phone (no files, no addresses leave it)
// so a pattern is easy to spot — "it always goes down a minute after this payload". The notes are plain text, in
// English like the crash report, because they are meant to be shared with whoever helps or pasted into a bug report.
// ---------------------------------------------------------------------------------------------

/** The payloads sent lately, kept in memory, so the crash diary can say what went out just before a console went down. */
object SendLog {
    data class Send(val at: Long, val host: String, val name: String)

    private const val MAX = 80
    private val sends = ArrayDeque<Send>()

    /** Remembers that [name] went to [host]. */
    fun note(host: String, name: String, now: Long = System.currentTimeMillis()) {
        val h = host.trim()
        if (h.isEmpty() || name.isBlank()) return
        synchronized(sends) {
            sends.addLast(Send(now, h, name))
            while (sends.size > MAX) sends.removeFirst()
        }
    }

    /** What went to [host] within the last [withinMs], oldest first. */
    fun recent(host: String, withinMs: Long, now: Long = System.currentTimeMillis()): List<Send> {
        val h = host.trim()
        return synchronized(sends) { sends.filter { it.host == h && now - it.at in 0..withinMs } }
    }

    fun clear() = synchronized(sends) { sends.clear() }
}

/** What every payload that went out is noted for: the double-load warning and the crash diary. */
fun notePayloadSent(host: String, name: String) {
    ReloadGuard.noteSent(host, name)
    SendLog.note(host, name)
    Stability.noteLoad(name)
}

object CrashDiary {
    private const val DIR = "crash-diary"

    /** At most this many notes are kept; the oldest go. */
    const val MAX_ENTRIES = 20

    /** How far back "what was sent just before" looks. */
    const val RECENT_WINDOW_MS = 10L * 60 * 1000

    /** How many of the last log lines are kept with a note. */
    const val TAIL_LINES = 40

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val human = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class Entry(val file: File, val whenMs: Long)

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** The text of one note (English, like the crash report: it is made to be shared). */
    fun format(host: String, label: String, lines: List<String>, sent: List<SendLog.Send>, now: Date): String = buildString {
        append("Crimson Multitool crash diary\n")
        append("When: ").append(human.format(now)).append('\n')
        append("Console: ").append(if (label.isNotBlank()) "$label ($host)" else host).append('\n')
        append('\n')
        append("Payloads sent in the last 10 minutes:\n")
        if (sent.isEmpty()) append("  (none)\n")
        else for (s in sent) append("  ").append(human.format(Date(s.at))).append("  ").append(s.name).append('\n')
        append('\n')
        append("Last lines before it went quiet:\n")
        val tail = if (lines.size > TAIL_LINES) lines.subList(lines.size - TAIL_LINES, lines.size) else lines
        for (l in tail) append(l).append('\n')
    }

    /** Writes a note for a console that went silent. Safe to call off the main thread. */
    fun record(ctx: Context, host: String, label: String, lines: List<String>, now: Long = System.currentTimeMillis()) {
        try {
            val d = dir(ctx)
            val f = File(d, "crash-" + stamp.format(Date(now)) + ".txt")
            val sent = SendLog.recent(host, RECENT_WINDOW_MS, now)
            f.writeText(format(host, label, lines, sent, Date(now)))
            Stability.noteCrash(sent.map { it.name })
            prune(d)
        } catch (_: Exception) {
        }
    }

    /** Keeps only the newest [MAX_ENTRIES] notes. */
    fun prune(d: File) {
        val files = d.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: return
        files.drop(MAX_ENTRIES).forEach { try { it.delete() } catch (_: Exception) {} }
    }

    /** The notes, newest first. */
    fun entries(ctx: Context): List<Entry> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }
            ?.map { Entry(it, parseWhen(it.name)) } ?: emptyList()

    fun read(entry: Entry): String = try { entry.file.readText() } catch (_: Exception) { "" }
    fun delete(entry: Entry) { try { entry.file.delete() } catch (_: Exception) {} }
    fun clearAll(ctx: Context) { dir(ctx).listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} } }

    /** The time in a note's file name, or 0 when it can't be read. */
    fun parseWhen(name: String): Long = try {
        stamp.parse(name.removePrefix("crash-").removeSuffix(".txt"))?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    /** A note's time, shown to the reader. */
    fun title(entry: Entry): String =
        if (entry.whenMs > 0) human.format(Date(entry.whenMs)) else entry.file.name
}

/** The Crash diary: the list of notes, each one openable, shareable and removable. */
@Composable
fun CrashDiaryDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(CrashDiary.entries(ctx)) }
    var report by remember { mutableStateOf(Stability.rows(ctx)) }
    var open by remember { mutableStateOf<CrashDiary.Entry?>(null) }

    val viewing = open
    if (viewing != null) {
        val text = remember(viewing) { CrashDiary.read(viewing) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(CrashDiary.title(viewing), maxLines = 1) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            },
            confirmButton = { TextButton(onClick = { shareText(ctx, text, CrashDiary.title(viewing)) }) { Text(str(R.string.crashdiary_share)) } },
            dismissButton = { TextButton(onClick = { open = null }) { Text(str(R.string.action_close)) } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.crashdiary_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(str(R.string.crashdiary_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The stability report: which payloads the console tends to go down after
                if (report.isNotEmpty()) {
                    Text(str(R.string.stab_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    Text(str(R.string.stab_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (r in report.take(12)) {
                        Text(
                            if (r.suspect) str(R.string.stab_suspect, r.name, r.crashes, r.loads)
                            else str(R.string.stab_row, r.name, r.loads, r.crashes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (r.suspect) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(onClick = {
                        Stability.clear(ctx)
                        report = Stability.rows(ctx)
                    }) { Text(str(R.string.stab_reset)) }
                }
                if (entries.isEmpty()) {
                    Text(str(R.string.crashdiary_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    for (e in entries) {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = { open = e }, modifier = Modifier.weight(1f)) {
                                Text(CrashDiary.title(e), modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(onClick = {
                                CrashDiary.delete(e)
                                entries = CrashDiary.entries(ctx)
                            }) { Text(str(R.string.action_delete)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } },
        dismissButton = {
            if (entries.isNotEmpty()) TextButton(onClick = {
                CrashDiary.clearAll(ctx)
                entries = CrashDiary.entries(ctx)
            }) { Text(str(R.string.crashdiary_clear_all)) }
        }
    )
}
