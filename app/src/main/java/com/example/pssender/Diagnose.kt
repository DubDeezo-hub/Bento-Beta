package com.example.pssender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------------------------
// "What's wrong?": a closer look than the console card's three lights. It checks the phone's own network, whether
// anything at all answers at the console's address, each service on its port (and on the ports it often uses
// instead), and the Memory tab's debugger, then says in plain words what is missing and offers the fix.
// The deciding part ([diagnose]) only looks at what was measured, so it can be tested without a console.
// ---------------------------------------------------------------------------------------------

/** What happened when Bento tried to connect to one port. */
enum class PortState {
    /** Something accepted the connection. */
    OPEN,

    /** The console answered "nothing here": it is on the network, but nothing listens on that port. */
    REFUSED,

    /** No answer at all within the time allowed. */
    SILENT,

    /** The phone has no way to that address (another network, no Wi-Fi). */
    NO_ROUTE
}

/** The services the check looks at. */
enum class DiagService { INSTALLER, LOADER, FTP, DEBUGGER }

/** What the check measured, for [diagnose]. */
class DiagInput(
    val type: ConsoleType,
    val host: String,
    /** Whether the phone has a Wi-Fi-like connection with a private address. */
    val onWifi: Boolean,
    /** The phone's network ("192.168.1.0/24") when the console's address is outside every network the phone is on, else null. */
    val otherNetwork: String?,
    /** Whether the console answered a ping (null when the phone couldn't ping). */
    val pinged: Boolean?,
    /** The port each service is set to (null when the field isn't a number). */
    val ports: Map<DiagService, Int?>,
    /** Every port that was tried and how it went. */
    val probes: Map<Int, PortState>,
    /** Recent lines of the console's log that look like problems (a payload that crashed, a refused request). */
    val logProblems: List<String> = emptyList()
)

/** One fix the check can offer. */
sealed class DiagFix {
    /** Set [service] to [port] for this console. */
    class UsePort(val service: DiagService, val port: Int) : DiagFix()

    /** Open the payload store. */
    object GetPayloads : DiagFix()

    /** Open etaHEN's settings (its FTP answers, so they can be changed from here). */
    object EtaHenSettings : DiagFix()

    /** Look for the console on the network. */
    object FindConsole : DiagFix()
}

/** How a finding is shown: good news, worth knowing, or the thing to fix. */
enum class DiagLevel { GOOD, INFO, WARN, BAD }

/** One line of the result. */
class DiagFinding(val level: DiagLevel, val title: String, val detail: String = "", val fix: DiagFix? = null)

object Diagnose {
    /** How long one port may take to answer. */
    const val TIMEOUT_MS = 1500

    /** The ports a service often uses instead of the one that is set, by console. */
    fun alternatives(type: ConsoleType, service: DiagService): List<Int> = when (service) {
        DiagService.INSTALLER -> listOf(12800)
        DiagService.LOADER -> if (type == ConsoleType.PS4) listOf(9090, 9020, 9021) else listOf(9021, 9020)
        DiagService.FTP -> if (type == ConsoleType.PS4) listOf(2121, 21, 1337) else listOf(2121, 1337, 21)
        DiagService.DEBUGGER -> listOf(DEBUG_PORT)
    }

    /** Every port worth trying for these settings. */
    fun portsToTry(type: ConsoleType, ports: Map<DiagService, Int?>): List<Int> =
        (ports.values.filterNotNull() + DiagService.entries.flatMap { alternatives(type, it) }).filter { it in 1..65535 }.distinct()

    /** The name of a service, in the app's language. */
    fun name(service: DiagService): String = AppText.get(
        when (service) {
            DiagService.INSTALLER -> R.string.service_installer
            DiagService.LOADER -> R.string.service_loader
            DiagService.FTP -> R.string.service_ftp
            DiagService.DEBUGGER -> R.string.tip_mem_debugger_title
        }
    )

    /** What is wrong (or right), most important first. */
    fun diagnose(i: DiagInput): List<DiagFinding> {
        val model = i.type.model
        val out = ArrayList<DiagFinding>()
        if (i.host.isBlank()) {
            out.add(DiagFinding(DiagLevel.BAD, AppText.get(R.string.diag_no_address_title), AppText.get(R.string.diag_no_address, model), DiagFix.FindConsole))
            return out
        }
        if (!i.onWifi) {
            out.add(DiagFinding(DiagLevel.BAD, AppText.get(R.string.diag_no_wifi_title), AppText.get(R.string.diag_no_wifi, model)))
        } else if (i.otherNetwork != null) {
            out.add(
                DiagFinding(
                    DiagLevel.WARN, AppText.get(R.string.diag_other_net_title, i.host),
                    AppText.get(R.string.diag_other_net, i.otherNetwork, model), DiagFix.FindConsole
                )
            )
        }

        val answered = i.probes.values.any { it == PortState.OPEN || it == PortState.REFUSED } || i.pinged == true
        if (!answered) {
            val noRoute = i.probes.isNotEmpty() && i.probes.values.all { it == PortState.NO_ROUTE }
            out.add(
                DiagFinding(
                    DiagLevel.BAD,
                    AppText.get(if (noRoute) R.string.diag_no_route_title else R.string.diag_silent_title, i.host),
                    AppText.get(R.string.diag_silent, model, i.type.hen),
                    DiagFix.FindConsole
                )
            )
            return out
        }

        fun open(port: Int?) = port != null && i.probes[port] == PortState.OPEN
        val homebrew = listOf(DiagService.INSTALLER, DiagService.LOADER, DiagService.FTP)
        val anyHomebrew = homebrew.any { s -> open(i.ports[s]) || alternatives(i.type, s).any { open(it) } }
        if (!anyHomebrew) {
            out.add(DiagFinding(DiagLevel.BAD, AppText.get(R.string.diag_no_homebrew_title, model), AppText.get(R.string.diag_no_homebrew, i.type.hen)))
            return out
        }

        var allGood = true
        for (s in homebrew) {
            val port = i.ports[s]
            if (port == null) {
                allGood = false
                out.add(DiagFinding(DiagLevel.BAD, AppText.get(R.string.diag_bad_port_title, name(s)), AppText.get(R.string.diag_bad_port)))
                continue
            }
            if (open(port)) {
                out.add(DiagFinding(DiagLevel.GOOD, AppText.get(R.string.diag_ok, name(s), port)))
                continue
            }
            allGood = false
            val moved = alternatives(i.type, s).firstOrNull { it != port && open(it) }
            if (moved != null) {
                out.add(
                    DiagFinding(
                        DiagLevel.WARN, AppText.get(R.string.diag_moved_title, name(s), moved, port),
                        AppText.get(R.string.diag_moved), DiagFix.UsePort(s, moved)
                    )
                )
            } else {
                // etaHEN's installer is a setting in its settings file, which can be changed while its FTP answers
                val ftpUp = i.ports[DiagService.FTP]?.let { open(it) } == true
                val fix = if (s == DiagService.INSTALLER && i.type == ConsoleType.PS5 && ftpUp) DiagFix.EtaHenSettings else fixFor(i.type, s)
                out.add(DiagFinding(DiagLevel.BAD, AppText.get(R.string.diag_down_title, name(s), port), adviceFor(i.type, s, port), fix))
            }
        }

        val dbgPort = i.ports[DiagService.DEBUGGER] ?: DEBUG_PORT
        out.add(
            if (open(dbgPort)) DiagFinding(DiagLevel.GOOD, AppText.get(R.string.diag_debugger_ok, dbgPort))
            else DiagFinding(
                DiagLevel.INFO, AppText.get(R.string.diag_debugger_off_title, dbgPort),
                AppText.get(R.string.diag_debugger_off, if (i.type == ConsoleType.PS4) "ps4debug-NG" else "ps5debug-NG"), DiagFix.GetPayloads
            )
        )
        if (i.logProblems.isNotEmpty()) {
            out.add(DiagFinding(DiagLevel.WARN, AppText.get(R.string.diag_log_title, i.logProblems.size), i.logProblems.joinToString("\n")))
        }
        if (allGood) out.add(0, DiagFinding(DiagLevel.GOOD, AppText.get(R.string.diag_all_good_title), AppText.get(R.string.diag_all_good)))
        return out
    }

    /** Lines of a console log that look like a problem. */
    private val PROBLEM = Regex("""\b(error|failed|failure|fail|panic|crash(ed)?|exception|denied|refused|segfault|fault|abort(ed)?|unable|cannot|can't|fatal|timed? ?out)\b""", RegexOption.IGNORE_CASE)

    /** The last few problem lines of [lines], newest last, without repeats (each cut to a readable length). */
    fun problemsIn(lines: List<String>, max: Int = 5): List<String> =
        lines.filter { PROBLEM.containsMatchIn(it) }.map { it.trim().take(160) }.distinct().takeLast(max)

    /** What the console's log says right now: lines arriving on [port] for [ms] milliseconds. Blocking; empty when nothing answers. */
    fun listenToLog(host: String, port: Int, ms: Long = 2000): List<String> {
        val out = ArrayList<String>()
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 1000)
                s.soTimeout = 250
                val reader = s.getInputStream().bufferedReader()
                val end = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < end && out.size < 2000) {
                    val line = try { reader.readLine() } catch (_: java.net.SocketTimeoutException) { continue } ?: break
                    out.add(line)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun adviceFor(type: ConsoleType, s: DiagService, port: Int): String = AppText.get(
        when (s) {
            DiagService.INSTALLER -> if (type == ConsoleType.PS4) R.string.diag_installer_ps4 else R.string.diag_installer_ps5
            DiagService.LOADER -> if (type == ConsoleType.PS4) R.string.diag_loader_ps4 else R.string.diag_loader_ps5
            DiagService.FTP -> if (type == ConsoleType.PS4) R.string.diag_ftp_ps4 else R.string.diag_ftp_ps5
            DiagService.DEBUGGER -> R.string.diag_debugger_off
        },
        port
    )

    private fun fixFor(type: ConsoleType, s: DiagService): DiagFix? =
        if (s == DiagService.FTP && type == ConsoleType.PS5) DiagFix.GetPayloads else null

    /** Tries one port. Blocks for up to [timeoutMs]. */
    fun probe(host: String, port: Int, timeoutMs: Int = TIMEOUT_MS): PortState =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            PortState.OPEN
        } catch (_: SocketTimeoutException) {
            PortState.SILENT
        } catch (_: NoRouteToHostException) {
            PortState.NO_ROUTE
        } catch (_: UnknownHostException) {
            PortState.NO_ROUTE
        } catch (e: ConnectException) {
            stateOf(e.message.orEmpty())
        } catch (e: Exception) {
            stateOf(e.message.orEmpty())
        }

    /** What a failed connection's message says happened. */
    fun stateOf(message: String): PortState {
        val m = message.uppercase()
        return when {
            "ECONNREFUSED" in m || "REFUSED" in m -> PortState.REFUSED
            "EHOSTUNREACH" in m || "ENETUNREACH" in m || "NO ROUTE" in m || "UNREACHABLE" in m -> PortState.NO_ROUTE
            else -> PortState.SILENT
        }
    }

    /** One ping with the phone's own ping tool. Null when it can't be run. */
    fun ping(host: String): Boolean? =
        try {
            val p = ProcessBuilder("ping", "-c", "1", "-W", "1", host).redirectErrorStream(true).start()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroy()
                false
            } else p.exitValue() == 0
        } catch (_: Exception) {
            null
        }

    /** Measures everything for [profile] (on the network: call from a background thread or coroutine). */
    suspend fun measure(profile: Profile, debuggerPort: Int?): DiagInput = coroutineScope {
        val host = profile.host.trim()
        val ports = mapOf(
            DiagService.INSTALLER to profile.installPort.trim().toIntOrNull(),
            DiagService.LOADER to profile.payloadPort.trim().toIntOrNull(),
            DiagService.FTP to profile.ftpPort.trim().toIntOrNull(),
            DiagService.DEBUGGER to debuggerPort
        )
        val networks = withContext(Dispatchers.IO) { scanNetworks(localAddresses(), ::routeSource) }
        val target = parseIpv4(host)
        val other = if (target != null && networks.isNotEmpty() && !target.isLoopbackAddress &&
            networks.none { sameSubnet(it.own, target, it.prefix) }
        ) networkLabel(networks[0]) else null
        if (host.isBlank()) return@coroutineScope DiagInput(profile.type, host, networks.isNotEmpty(), null, null, ports, emptyMap())
        val tries = portsToTry(profile.type, ports)
        val probes = tries.map { p -> async(Dispatchers.IO) { p to probe(host, p) } }
        val pinged = async(Dispatchers.IO) { ping(host) }
        // The console log: what Bento's log view already has, plus whatever arrives in two seconds now
        val seen = KernelLog.lines.toList()
        val logPort = KernelLog.port.trim().toIntOrNull() ?: KernelLog.DEFAULT_PORT
        val heard = async(Dispatchers.IO) { if (KernelLog.running) emptyList() else listenToLog(host, logPort) }
        DiagInput(
            profile.type, host,
            onWifi = networks.isNotEmpty() || (target?.isLoopbackAddress == true),
            otherNetwork = other,
            pinged = pinged.await(),
            ports = ports,
            probes = probes.awaitAll().toMap(),
            logProblems = problemsIn(seen + heard.await())
        )
    }

    /** The last result, in English, for the debug info. */
    @Volatile
    var lastSummary: List<String> = emptyList()
        private set

    /** Keeps a short English summary of [input] for the debug info (the findings are in the app's language). */
    fun remember(input: DiagInput) {
        val lines = ArrayList<String>()
        lines.add("Wi-Fi: " + (if (input.onWifi) "yes" else "no") + (if (input.otherNetwork != null) ", console outside ${input.otherNetwork}" else ""))
        input.pinged?.let { lines.add("Ping: " + if (it) "answers" else "no answer") }
        lines.add("Ports: " + input.probes.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value.name.lowercase()}" })
        lastSummary = lines
    }
}

/**
 * The "What's wrong?" window for [profile]. [onFix] carries out a fix the user picked (it may close the window).
 */
@Composable
fun DiagnoseDialog(profile: Profile, debuggerPort: Int?, onFix: (DiagFix) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var run by remember { mutableStateOf(0) }
    var findings by remember { mutableStateOf<List<DiagFinding>?>(null) }
    LaunchedEffect(run, profile.host, profile.installPort, profile.payloadPort, profile.ftpPort) {
        findings = null
        val input = Diagnose.measure(profile, debuggerPort)
        Diagnose.remember(input)
        findings = Diagnose.diagnose(input)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { TipTitle(str(R.string.diag_title), Tip.DIAGNOSE, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val list = findings
                if (list == null) {
                    Text(str(R.string.diag_checking, profile.host.trim().ifBlank { "?" }), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.padding(top = 4.dp))
                } else {
                    list.forEach { f -> FindingRow(f, profile.type, onFix) }
                    // Everything a helper would ask for, ready to paste (addresses and links left out)
                    TextButton(onClick = {
                        copyText(ctx, "Bento", DebugInfo.build(ctx), AppText.get(R.string.diag_debug_copied))
                    }) { Text(str(R.string.diag_debug_copy)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } },
        dismissButton = {
            TextButton(enabled = findings != null, onClick = { run++ }) { Text(str(R.string.diag_again)) }
        }
    )
}

@Composable
private fun FindingRow(f: DiagFinding, type: ConsoleType, onFix: (DiagFix) -> Unit) {
    val sc = statusColors()
    val cs = MaterialTheme.colorScheme
    val tone = when (f.level) {
        DiagLevel.GOOD -> sc.good
        DiagLevel.INFO -> cs.onSurfaceVariant
        DiagLevel.WARN -> sc.warn
        DiagLevel.BAD -> sc.bad
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        androidx.compose.foundation.layout.Box(
            Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(tone)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (f.detail.isNotEmpty()) Text(f.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            when (val fix = f.fix) {
                is DiagFix.UsePort -> TextButton(onClick = { onFix(fix) }) { Text(str(R.string.diag_use_port, fix.port)) }
                DiagFix.GetPayloads -> TextButton(onClick = { onFix(fix) }) { Text(str(R.string.store_open)) }
                DiagFix.EtaHenSettings -> TextButton(onClick = { onFix(fix) }) { Text(str(R.string.etahen_title)) }
                DiagFix.FindConsole -> TextButton(onClick = { onFix(fix) }) { Text(str(R.string.find_button, type.model)) }
                null -> {}
            }
        }
    }
}
