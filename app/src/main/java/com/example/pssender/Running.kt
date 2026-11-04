package com.example.pssender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// What's running: one look at which homebrew services answer on the console right now, each with its port. The ports
// are the well-known ones (the payload loaders, the FTP servers, the debugger, the console logs, the package installer,
// websrv and shsrv) plus whatever this console is set to use. Only the console's own address is asked.
// ---------------------------------------------------------------------------------------------

/** What a port is known for. */
enum class RunKind { INSTALLER, LOADER, FTP, DEBUGGER, LOG, WEB, SHELL }

/** One port to look at, and what usually listens there. */
data class RunPort(val port: Int, val kind: RunKind)

object Running {
    /** The ports homebrew usually uses, by console. */
    fun known(type: ConsoleType): List<RunPort> = if (type == ConsoleType.PS4) listOf(
        RunPort(12800, RunKind.INSTALLER),
        RunPort(9090, RunKind.LOADER), RunPort(9020, RunKind.LOADER),
        RunPort(2121, RunKind.FTP), RunPort(21, RunKind.FTP),
        RunPort(DEBUG_PORT, RunKind.DEBUGGER),
        RunPort(3232, RunKind.LOG)
    ) else listOf(
        RunPort(8844, RunKind.INSTALLER), RunPort(12800, RunKind.INSTALLER),
        RunPort(9021, RunKind.LOADER), RunPort(9020, RunKind.LOADER),
        RunPort(2121, RunKind.FTP), RunPort(1337, RunKind.FTP), RunPort(21, RunKind.FTP),
        RunPort(DEBUG_PORT, RunKind.DEBUGGER),
        RunPort(3232, RunKind.LOG), RunPort(ETAHEN_KLOG_PORT, RunKind.LOG),
        RunPort(8080, RunKind.WEB),
        RunPort(2323, RunKind.SHELL)
    )

    /**
     * The ports to look at for a console of [type] set to [installer], [loader] and [ftp]: its own ports first (a port
     * typed by hand counts too), then the well-known ones, each port once.
     */
    fun portsFor(type: ConsoleType, installer: Int?, loader: Int?, ftp: Int?): List<RunPort> {
        val own = listOfNotNull(
            installer?.let { RunPort(it, RunKind.INSTALLER) },
            loader?.let { RunPort(it, RunKind.LOADER) },
            ftp?.let { RunPort(it, RunKind.FTP) }
        ).filter { it.port in 1..65535 }
        return (own + known(type)).distinctBy { it.port }
    }

    /** The name of what listens on a port of [kind], in the app's language. */
    fun label(kind: RunKind): String = AppText.get(
        when (kind) {
            RunKind.INSTALLER -> R.string.service_installer
            RunKind.LOADER -> R.string.service_loader
            RunKind.FTP -> R.string.service_ftp
            RunKind.DEBUGGER -> R.string.tip_mem_debugger_title
            RunKind.LOG -> R.string.klog_title
            RunKind.WEB -> R.string.running_web
            RunKind.SHELL -> R.string.running_shell
        }
    )
}

/** The What's running window for [profile]: each port with a lit dot when something answers there. */
@Composable
fun RunningDialog(profile: Profile, onClose: () -> Unit) {
    val ports = remember(profile) {
        Running.portsFor(profile.type, profile.installPort.trim().toIntOrNull(), profile.payloadPort.trim().toIntOrNull(), profile.ftpPort.trim().toIntOrNull())
    }
    var open by remember(profile) { mutableStateOf<Map<Int, Boolean>?>(null) }
    var round by remember { mutableStateOf(0) }
    val host = profile.host.trim()
    LaunchedEffect(profile, round) {
        open = null
        if (host.isEmpty()) return@LaunchedEffect
        // All ports at once, so the look takes as long as the slowest port (a second and a half at most)
        open = withContext(Dispatchers.IO) {
            coroutineScope {
                ports.map { p -> async { p.port to (Diagnose.probe(host, p.port) == PortState.OPEN) } }.awaitAll().toMap()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.running_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (host.isEmpty()) {
                    Text(str(R.string.files_need_ip), style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                Text(str(R.string.running_hint, host), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val o = open
                if (o == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                for (p in ports) {
                    val up = o?.get(p.port)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Box(
                            modifier = Modifier.size(12.dp).background(
                                when (up) {
                                    true -> statusColors().good
                                    false -> MaterialTheme.colorScheme.outlineVariant
                                    null -> MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                CircleShape
                            )
                        )
                        Text(Running.label(p.kind), modifier = Modifier.weight(1f).padding(start = 10.dp))
                        Text(
                            p.port.toString() + "  " + when (up) {
                                true -> str(R.string.running_on)
                                false -> str(R.string.running_off)
                                null -> "…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (up == true) statusColors().good else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } },
        dismissButton = { TextButton(enabled = open != null, onClick = { round++ }) { Text(str(R.string.files_refresh)) } }
    )
}
