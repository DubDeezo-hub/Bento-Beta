package com.example.pssender

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// Automation: things that happen by themselves. Android limits what an app may start in the background, so these are
// the ones that work reliably: the online alert (with the console's chain one tap away), a daily snapshot of the
// console's settings while it answers, and the switches for the smart chain and the black box, all in one place.
// ---------------------------------------------------------------------------------------------

object DailySnapshot {
    private const val KEY = "daily_snapshot"
    private const val JOB_ID = 4612
    private const val PERIOD_MS = 24L * 60 * 60 * 1000

    fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!on) {
            js.cancel(JOB_ID)
            return
        }
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, DailySnapshotJob::class.java))
            .setPeriodic(PERIOD_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED) // the console is on the home Wi-Fi
            .setPersisted(true)
            .build()
        try { js.schedule(job) } catch (_: Exception) {}
    }
}

/** Once a day: a settings snapshot of each saved console that answers (see Snapshots.kt). */
class DailySnapshotJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val ctx = applicationContext
        Thread {
            try {
                for (p in ProfileStore.load(ctx)) {
                    val port = p.ftpPort.trim().toIntOrNull() ?: continue
                    if (p.host.isBlank() || !OnlineAlert.answers(p)) continue
                    try {
                        ConfigSnapshots.take(ctx, FtpSettings(p.host.trim(), port, p.ftpUser.trim().ifEmpty { "anonymous" }, "", p.type.model), p.id, ConfigSnapshots.folderFor(p.type))
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}

@Composable
fun AutomationDialog(console: Profile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(OnlineAlert.isOn(ctx, console.id)) }
    var daily by remember { mutableStateOf(DailySnapshot.isOn(ctx)) }
    var smart by remember { mutableStateOf(SmartChain.on) }
    var box by remember { mutableStateOf(BlackBox.on) }
    var controls by remember { mutableStateOf(ConsoleControls.isOn(ctx)) }
    val chain = remember { pickQuickChain(ChainStore.load(ctx), AutoChain.get(ctx, console.id)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.auto_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(R.string.auto_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Rule(
                    str(R.string.auto_online, console.name),
                    if (chain != null) str(R.string.auto_online_chain, chain.name) else str(R.string.auto_online_plain),
                    online
                ) {
                    online = it
                    OnlineAlert.set(ctx, console.id, it)
                }
                Rule(str(R.string.auto_daily), str(R.string.auto_daily_hint, console.type.model), daily) {
                    daily = it
                    DailySnapshot.set(ctx, it)
                }
                Rule(str(R.string.chain_smart_title), str(R.string.chain_smart_hint), smart) {
                    smart = it
                    SmartChain.set(ctx, it)
                }
                Rule(str(R.string.blackbox_title), str(R.string.blackbox_detail), box) {
                    box = it
                    BlackBox.set(ctx, it)
                }
                Rule(str(R.string.controls_title), str(R.string.controls_hint), controls) {
                    controls = it
                    ConsoleControls.set(ctx, it)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_done)) } }
    )
}

@Composable
private fun Rule(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
