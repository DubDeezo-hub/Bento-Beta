package com.example.pssender

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

// ---------------------------------------------------------------------------------------------
// Helpers for sharing files from the phone (see FileServer.kt): link names, secret tokens, picking the phone's address,
// and opening a file at a position. No Android parts, so they can be tested on their own.
// ---------------------------------------------------------------------------------------------

private const val MAX_LINK_NAME = 100
private const val MAX_EXTENSION = 8

/**
 * [name] changed so it is safe inside a web link and a header: only ASCII letters, digits, dot, dash and underscore.
 * Other characters become an underscore (a run of them one underscore), and the ending (like ".pkg") is kept.
 * A name with nothing usable in it becomes "file". The result is never longer than 100 characters.
 */
fun safeLinkName(name: String): String {
    fun clean(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_'
            if (ok) sb.append(ch) else if (sb.isEmpty() || sb[sb.length - 1] != '_') sb.append('_')
        }
        return sb.toString()
    }

    val dot = name.lastIndexOf('.')
    var extension = if (dot >= 0) clean(name.substring(dot + 1)).trim('_', '.', '-') else ""
    if (extension.length > MAX_EXTENSION) extension = ""
    var base = clean(if (dot >= 0) name.substring(0, dot) else name).trim('_', '.', '-')
    if (base.isEmpty()) base = "file"
    val room = MAX_LINK_NAME - (if (extension.isEmpty()) 0 else extension.length + 1)
    if (base.length > room) base = base.substring(0, room).trimEnd('_', '.', '-').ifEmpty { "file" }
    return if (extension.isEmpty()) base else "$base.$extension"
}

/** A random secret for a link: 24 hexadecimal characters (96 bits), which nobody can guess. */
fun newToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(12)
    random.nextBytes(bytes)
    val sb = StringBuilder()
    for (b in bytes) sb.append("%02x".format(b.toInt() and 0xff))
    return sb.toString()
}

// ----- the phone's address -----

/** One IPv4 address of the phone, with the length of its network prefix (24 for a mask of 255.255.255.0). */
class LocalAddress(val address: Inet4Address, val prefix: Int, val interfaceName: String)

/** [text] as an IPv4 address, or null when it isn't one (a name is not looked up). */
fun parseIpv4(text: String): Inet4Address? {
    val t = text.trim()
    val parts = t.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for (i in 0 until 4) {
        val p = parts[i]
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
        val v = p.toInt()
        if (v > 255) return null
        bytes[i] = v.toByte()
    }
    return InetAddress.getByAddress(bytes) as Inet4Address
}

private fun asInt(a: Inet4Address): Int {
    val b = a.address
    return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
        ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
}

/** True when [a] and [b] are in the same network, going by the first [prefix] bits (24 when the prefix makes no sense). */
fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
    val bits = if (prefix in 1..32) prefix else 24
    val mask = if (bits == 32) -1 else -1 shl (32 - bits)
    return (asInt(a) and mask) == (asInt(b) and mask)
}

/** True for the names of Wi-Fi, hotspot and cable connections (wlan0, swlan0, ap0, eth0, ...), not the mobile network (rmnet...). */
fun looksLikeWifi(interfaceName: String): Boolean {
    val n = interfaceName.lowercase()
    return n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("ap") ||
        n.startsWith("eth") || n.startsWith("p2p")
}

/**
 * Which of the phone's addresses the console should download from: the one in the console's own network. In order:
 * 1) a console on this very phone (127.0.0.1, for testing) is reached through the loopback address;
 * 2) an address of the phone that is in the same network as [console];
 * 3) when the phone's addresses could not be listed at all, the address the system would use to reach the console
 *    ([route]);
 * 4) the private address of a Wi-Fi-like connection (not the mobile network);
 * 5) null: the phone has no usable address.
 */
fun chooseServeAddress(console: String, locals: List<LocalAddress>, route: (Inet4Address) -> Inet4Address?): Inet4Address? {
    val target = parseIpv4(console)
    if (target != null && target.isLoopbackAddress) return InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)) as Inet4Address
    val usable = locals.filter { !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress && !it.address.isAnyLocalAddress }
    if (target != null) {
        usable.firstOrNull { sameSubnet(it.address, target, it.prefix) }?.let { return it.address }
        if (locals.isEmpty()) {
            val routed = route(target)
            if (routed != null && routed.isSiteLocalAddress) return routed
        }
    }
    val wifi = usable.filter { it.address.isSiteLocalAddress && looksLikeWifi(it.interfaceName) }
    return (wifi.firstOrNull { it.interfaceName.lowercase().startsWith("wlan") } ?: wifi.firstOrNull())?.address
}

/** The phone's IPv4 addresses on connections that are up (never the loopback). Empty when the system won't say. */
fun localAddresses(): List<LocalAddress> {
    val found = ArrayList<LocalAddress>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return found
        for (nif in interfaces.toList()) {
            try {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address) found.add(LocalAddress(a, ia.networkPrefixLength.toInt(), nif.name))
                }
            } catch (_: Exception) {
            }
        }
    } catch (_: Exception) {
    }
    return found
}

/** The address the system would send from to reach [target] (nothing is sent), or null when there is no route. */
fun routeSource(target: Inet4Address): Inet4Address? =
    try {
        DatagramSocket().use { socket ->
            socket.connect(target, 9)
            socket.localAddress as? Inet4Address
        }
    } catch (_: Exception) {
        null
    }

// ----- opening a file at a position -----

/**
 * Moves [input] to byte [from]. Jumps there when the file allows it; otherwise (for example a pipe, which some apps
 * hand out) reads and drops the bytes up to there. Throws when the file is shorter than [from].
 */
fun seekOrSkip(input: FileInputStream, from: Long) {
    if (from <= 0L) return
    try {
        input.channel.position(from)
        return
    } catch (_: IOException) {
        // not seekable: fall through to reading
    }
    var left = from
    val scratch = ByteArray(64 * 1024)
    while (left > 0L) {
        val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
        if (n < 0) throw EOFException("The file is shorter than $from bytes")
        left -= n
    }
}

/** A plain file on the phone's storage as a [ByteSource]. */
class FileByteSource(private val file: File) : ByteSource {
    override fun open(from: Long): InputStream {
        val input = FileInputStream(file)
        try {
            seekOrSkip(input, from)
        } catch (e: Exception) {
            input.close()
            throw e
        }
        return input
    }
}
