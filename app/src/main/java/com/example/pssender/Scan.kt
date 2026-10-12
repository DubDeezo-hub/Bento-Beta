package com.example.pssender // change to match your project's package name

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------------------------
// Finding the console on the Wi-Fi network. The scan only runs when the person asks for it. It connects to the console's
// usual ports on the addresses of the phone's own network and closes the connection again; nothing is sent, and nothing
// leaves the local network. No Android screens in here, so it can be tested on its own.
// ---------------------------------------------------------------------------------------------

/** How many connections are tried at the same time. */
const val SCAN_PARALLEL = 64

/** How long one connection attempt may take. A device on the same Wi-Fi answers within a few milliseconds; a sleeping one within a quarter second. */
const val SCAN_TIMEOUT_MS = 450

/** At most this many networks are scanned (the Wi-Fi and, say, a hotspot), so one scan stays short. */
const val SCAN_NETWORKS_MAX = 2

/** The services a console offers, as far as the scan can tell them apart by their ports. */
enum class ScanService { INSTALLER, LOADER, FTP }

/**
 * Which ports belong to which service. The console's own settings come first, then the ports that etaHEN and the usual
 * FTP payload use.
 */
class ScanPorts(val installer: List<Int>, val loader: List<Int>, val ftp: List<Int>) {
    /** Every port to try, each one once. */
    val all: List<Int> = (installer + loader + ftp).distinct()

    /** The services that answered, when the ports in [open] accepted a connection. */
    fun servicesOf(open: Collection<Int>): List<ScanService> {
        val found = ArrayList<ScanService>()
        if (installer.any { it in open }) found.add(ScanService.INSTALLER)
        if (loader.any { it in open }) found.add(ScanService.LOADER)
        if (ftp.any { it in open }) found.add(ScanService.FTP)
        return found
    }
}

/** The ports to try for [profile]: its own three, and the usual ones (12800 installer, 9021 loader, 1337 and 2121 FTP). */
fun scanPortsFor(profile: Profile): ScanPorts {
    fun own(text: String): List<Int> = listOfNotNull(text.trim().toIntOrNull()?.takeIf { it in 1..65535 })
    return ScanPorts(
        installer = (own(profile.installPort) + 12800).distinct(),
        loader = (own(profile.payloadPort) + 9021).distinct(),
        ftp = (own(profile.ftpPort) + listOf(1337, 2121)).distinct()
    )
}

/** An address of the network that answered on some of the console's ports. */
class ScanHit(val host: String, val ports: List<Int>, val services: List<ScanService>) {
    /** True when the installer or the loader answered: that is what a console running etaHEN looks like. FTP alone could be any device. */
    val strong: Boolean get() = ScanService.INSTALLER in services || ScanService.LOADER in services
}

/** The hits with the most likely console first, then by address. */
fun sortHits(hits: List<ScanHit>): List<ScanHit> =
    hits.sortedWith(compareBy<ScanHit>({ !it.strong }, { -it.services.size }, { addressKey(it.host) }))

private fun addressKey(host: String): Long = parseIpv4(host)?.let { ipToInt(it).toLong() and 0xffffffffL } ?: Long.MAX_VALUE

private fun ipToInt(a: Inet4Address): Int {
    val b = a.address
    return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
}

private fun intToIp(v: Int): String =
    "${(v ushr 24) and 0xff}.${(v ushr 16) and 0xff}.${(v ushr 8) and 0xff}.${v and 0xff}"

// ----- which addresses to try -----

/** A network to scan: an address of the phone in it, and the length of the network prefix (24 to 30). */
class ScanNetwork(val own: Inet4Address, val prefix: Int, val interfaceName: String)

private fun maskOf(prefix: Int): Int = -1 shl (32 - prefix)

private fun baseOf(n: ScanNetwork): Int = ipToInt(n.own) and maskOf(n.prefix)

/** "192.168.1.0/24" */
fun networkLabel(n: ScanNetwork): String = intToIp(baseOf(n)) + "/" + n.prefix

private fun wifiFirst(interfaceName: String): Int {
    val n = interfaceName.lowercase()
    return when {
        n.startsWith("wlan") -> 0
        n.startsWith("eth") -> 1
        else -> 2
    }
}

/**
 * The networks worth scanning, from the phone's addresses: private addresses on a Wi-Fi-like connection (never the mobile
 * network), Wi-Fi first, at most [SCAN_NETWORKS_MAX]. A large network is cut down to the 254 addresses around the phone's
 * own; a smaller one (up to /30) is scanned whole. When the system lists no addresses at all, [route] says which one it
 * would use to reach a typical home router, as the file server does.
 */
fun scanNetworks(locals: List<LocalAddress>, route: (Inet4Address) -> Inet4Address? = { null }): List<ScanNetwork> {
    val usable = locals.filter { l ->
        val a = l.address
        !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress && a.isSiteLocalAddress &&
            looksLikeWifi(l.interfaceName) && !l.interfaceName.lowercase().startsWith("p2p")
    }.sortedBy { wifiFirst(it.interfaceName) }
    val found = ArrayList<ScanNetwork>()
    for (l in usable) {
        val bits = when (l.prefix) {
            in 24..30 -> l.prefix
            in 1..23 -> 24
            else -> continue // a /31 or /32 has no neighbors to look at
        }
        val net = ScanNetwork(l.address, bits, l.interfaceName)
        if (found.none { it.prefix == net.prefix && baseOf(it) == baseOf(net) }) found.add(net)
        if (found.size >= SCAN_NETWORKS_MAX) break
    }
    if (found.isEmpty() && locals.isEmpty()) {
        val routed = parseIpv4("192.168.1.1")?.let(route)
        if (routed != null && routed.isSiteLocalAddress) found.add(ScanNetwork(routed, 24, ""))
    }
    return found
}

/** Every address in [n] that a device can have: all but the network's own address and its broadcast address. The phone's own is included. */
fun hostsToScan(n: ScanNetwork): List<String> {
    val base = baseOf(n)
    val count = 1 shl (32 - n.prefix)
    return (1 until count - 1).map { intToIp(base + it) }
}

// ----- trying them -----

/** True when something at [host] accepts a connection on [port] within [timeoutMs]. Blocks; it connects and closes, nothing is sent. */
fun portAnswers(host: String, port: Int, timeoutMs: Int): Boolean =
    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

/**
 * Tries every port of [ports] on every one of [hosts] with [probe] and returns the hosts where something answered. At most
 * [parallel] probes run at the same time. [onProgress] gets the number of probes finished and the total; [onHits] gets the
 * full list of hits so far each time a probe finds an open port. Both are called one at a time. A probe that throws counts as
 * "no answer"; cancelling the caller stops the scan.
 */
suspend fun scanHosts(
    hosts: List<String>,
    ports: ScanPorts,
    probe: suspend (String, Int) -> Boolean,
    parallel: Int = SCAN_PARALLEL,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    onHits: (List<ScanHit>) -> Unit = {}
): List<ScanHit> {
    val list = ports.all
    val total = hosts.size * list.size
    if (total == 0) return emptyList()
    val lock = Any()
    val open = LinkedHashMap<String, MutableList<Int>>()
    var done = 0

    fun snapshot(): List<ScanHit> =
        sortHits(open.map { (host, found) -> ScanHit(host, found.sorted(), ports.servicesOf(found)) })

    coroutineScope {
        val gate = Semaphore(parallel.coerceAtLeast(1))
        for (host in hosts) {
            for (port in list) {
                launch {
                    val answered = gate.withPermit {
                        try {
                            probe(host, port)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                    }
                    synchronized(lock) {
                        done++
                        if (answered) {
                            open.getOrPut(host) { ArrayList() }.add(port)
                            onHits(snapshot())
                        }
                        onProgress(done, total)
                    }
                }
            }
        }
    }
    return synchronized(lock) { snapshot() }
}

/**
 * Scans [networks] for the console's ports on threads of its own (so the rest of the app is not held up), and returns the hits.
 * See [scanHosts] for [onProgress] and [onHits].
 */
suspend fun scanWifi(
    networks: List<ScanNetwork>,
    ports: ScanPorts,
    timeoutMs: Int = SCAN_TIMEOUT_MS,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    onHits: (List<ScanHit>) -> Unit = {}
): List<ScanHit> {
    val hosts = networks.flatMap { hostsToScan(it) }.distinct()
    val counter = AtomicInteger()
    val pool = Executors.newFixedThreadPool(SCAN_PARALLEL) { job ->
        Thread(job, "bento-scan-" + counter.incrementAndGet()).apply { isDaemon = true }
    }
    val dispatcher = pool.asCoroutineDispatcher()
    try {
        return scanHosts(
            hosts, ports,
            probe = { host, port -> withContext(dispatcher) { portAnswers(host, port, timeoutMs) } },
            parallel = SCAN_PARALLEL, onProgress = onProgress, onHits = onHits
        )
    } finally {
        pool.shutdown() // attempts that are still waiting for their answer finish by themselves within the timeout
    }
}
