package com.example.pssender // change to match your project's package name

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// "Find my PS5": looks for the console on the phone's Wi-Fi network (see Scan.kt) and offers its address.
// ---------------------------------------------------------------------------------------------

/**
 * Lets any screen ask for the "Find my PS5" window without knowing where it is shown: the help page of a console that
 * can't be reached has a button for it. The main screen watches [asked] and opens the window.
 */
object FindConsole {
    private val flag = MutableStateFlow(false)

    /** True from the moment [ask] is called until the main screen has [handled] it. */
    val asked: StateFlow<Boolean> get() = flag

    fun ask() {
        flag.value = true
    }

    fun handled() {
        flag.value = false
    }
}

private enum class FindPhase { SCANNING, DONE, STOPPED, NO_WIFI }

/**
 * The window that scans the Wi-Fi network for the console. The scan starts as soon as it opens; the addresses it finds
 * appear while it runs. [savedHost] is the address saved for the console now, [otherConsoles] maps the addresses saved for
 * the other consoles to their names. [onUse] is told the address the person picked.
 */
@Composable
fun FindConsoleDialog(
    consoleName: String,
    savedHost: String,
    ports: ScanPorts,
    otherConsoles: Map<String, String>,
    onUse: (String) -> Unit,
    onClose: () -> Unit
) {
    var runs by remember { mutableStateOf(0) } // goes up to start another scan
    var stopped by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(FindPhase.SCANNING) }
    var hits by remember { mutableStateOf<List<ScanHit>>(emptyList()) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var where by remember { mutableStateOf("") }

    // Starts again whenever [runs] changes; setting [stopped] ends the scan that is running (and starts nothing new)
    LaunchedEffect(runs, stopped) {
        if (stopped) return@LaunchedEffect
        phase = FindPhase.SCANNING
        hits = emptyList()
        done = 0
        total = 0
        val networks = withContext(Dispatchers.IO) { scanNetworks(localAddresses(), ::routeSource) }
        if (networks.isEmpty()) {
            phase = FindPhase.NO_WIFI
            return@LaunchedEffect
        }
        where = networks.joinToString(", ") { networkLabel(it) }
        scanWifi(
            networks, ports,
            onProgress = { finished, all ->
                done = finished
                total = all
            },
            onHits = { hits = it }
        )
        phase = FindPhase.DONE
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.find_button)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (phase == FindPhase.NO_WIFI) {
                    Text(str(R.string.find_no_wifi))
                } else {
                    if (phase == FindPhase.SCANNING) {
                        Text(str(R.string.find_scanning, where))
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total.toFloat() else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (hits.isNotEmpty()) {
                        Text(str(R.string.find_pick, consoleName))
                        hits.forEach { hit -> HitRow(hit, savedHost, otherConsoles[hit.host]) { onUse(hit.host) } }
                    } else if (phase != FindPhase.SCANNING) {
                        Text(str(R.string.find_none))
                        Text(str(R.string.find_none_try), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        str(R.string.find_privacy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (phase == FindPhase.SCANNING) {
                TextButton(onClick = {
                    stopped = true
                    phase = FindPhase.STOPPED
                }) { Text(str(R.string.action_stop)) }
            } else {
                TextButton(onClick = {
                    stopped = false
                    runs++
                }) { Text(str(R.string.find_again)) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

/** One address that answered: the address, which services answered on it, and a button to use it (not for the address already saved). */
@Composable
private fun HitRow(hit: ScanHit, savedHost: String, usedBy: String?, onUse: () -> Unit) {
    val installer = str(R.string.service_installer)
    val loader = str(R.string.service_loader)
    val ftp = str(R.string.service_ftp)
    val names = hit.services.map { service ->
        when (service) {
            ScanService.INSTALLER -> installer
            ScanService.LOADER -> loader
            ScanService.FTP -> ftp
        }
    }
    val isSaved = hit.host == savedHost
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(hit.host, style = MaterialTheme.typography.titleSmall)
            Text(joinList(names), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isSaved) {
                Text(str(R.string.find_saved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            } else if (usedBy != null) {
                Text(
                    str(R.string.find_used_by, usedBy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!isSaved) TextButton(onClick = onUse) { Text(str(R.string.action_use)) }
    }
}
