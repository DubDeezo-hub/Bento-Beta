package com.example.pssender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// The session report: what was sent and what happened (payloads, chains, files, the console going silent), one line
// each with the time, kept on the phone (the last few hundred lines) and shared in one tap. No addresses are written.
// And the console controls: an optional notification with the console's chain one tap away, which Wear OS watches show
// too (a watch shows the phone's notifications with their buttons).
// ---------------------------------------------------------------------------------------------

object SessionLog {
    const val MAX_LINES = 300
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()

    @Volatile
    private var file: File? = null

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "session-log.txt")
        file = f
        try {
            if (f.isFile) synchronized(lines) { lines.clear(); f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) } }
        } catch (_: Exception) {
        }
    }

    /** Notes [text] with the time now. */
    fun note(text: String, now: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        val line = clock.format(Date(now)) + "  " + withoutAddresses(text.replace('\n', ' ').trim())
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        try {
            file?.let { f ->
                f.appendText(line + "\n")
                if (f.length() > 64 * 1024) f.writeText(snapshot().joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        try { file?.delete() } catch (_: Exception) {}
    }

    /** The report to share: a header and the lines. */
    fun report(model: String, firmware: String): String =
        "Crimson Multitool session report\nConsole: $model ${firmware.ifBlank { "?" }}\n\n" + snapshot().joinToString("\n")
}

/** "Console controls": an optional notification with the selected console's chain one tap away; watches show it too. */
object ConsoleControls {
    private const val KEY = "console_controls"
    private const val CHANNEL_ID = "console_controls"
    private const val NOTIF_ID = 46

    fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        refresh(ctx)
    }

    /** Shows the notification (with the chain as it is now), or takes it away when switched off. */
    fun refresh(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!isOn(ctx)) {
            nm.cancel(NOTIF_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, AppText.get(R.string.controls_channel), NotificationManager.IMPORTANCE_LOW))
        val console = selectedConsole(ctx)
        val chain = quickChain(ctx)
        val open = PendingIntent.getActivity(
            ctx, 20, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(console?.name ?: AppText.get(R.string.app_name))
            .setContentText(if (chain != null) AppText.get(R.string.controls_text, chain.name) else AppText.get(R.string.controls_no_chain))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(false) // shown on a paired watch too
        if (chain != null) {
            val run = PendingIntent.getActivity(
                ctx, 21,
                Intent(ctx, MainActivity::class.java).setAction(ACTION_RUN_CHAIN).putExtra(EXTRA_CHAIN_ID, chain.id)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, AppText.get(R.string.online_run_chain, chain.name), run).build())
        }
        nm.notify(NOTIF_ID, b.build())
    }
}

@Composable
fun SessionDialog(console: Profile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf(SessionLog.snapshot()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.session_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(str(R.string.session_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lines.isEmpty()) Text(str(R.string.session_empty))
                lines.asReversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = lines.isNotEmpty(), onClick = {
                shareText(ctx, SessionLog.report(console.type.model, ConsoleFirmware.get(ctx, console.id)), AppText.get(R.string.session_title))
            }) { Text(str(R.string.crashdiary_share)) }
        },
        dismissButton = {
            TextButton(enabled = lines.isNotEmpty(), onClick = { SessionLog.clear(); lines = emptyList() }) { Text(str(R.string.crashdiary_clear_all)) }
        }
    )
}
