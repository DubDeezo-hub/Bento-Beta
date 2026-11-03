package com.example.pssender

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// ---------------------------------------------------------------------------------------------
// Payload developer mode: for people building payloads. Point it at the link of your build (a release asset or any
// direct link to the .elf or .bin); while the window is open it looks every 15 seconds, and a new build is checked
// (a real 64-bit ELF for the console, not cut short), put on the shelf and sent to the console, with the console log
// right there to see what it prints.
// ---------------------------------------------------------------------------------------------

/** What the ELF check says about a payload file. */
enum class ElfVerdict(val res: Int) {
    OK(R.string.dev_elf_ok),
    NOT_ELF(R.string.dev_elf_not_elf),
    NOT_64(R.string.dev_elf_not_64),
    WRONG_MACHINE(R.string.dev_elf_wrong_machine),
    TRUNCATED(R.string.dev_elf_truncated)
}

/**
 * Checks [b] as a payload for the console: an ELF file (a .bin is a raw payload and isn't checked: null), 64-bit,
 * little-endian, for x86-64 (the PS4's and PS5's processor), with its program headers inside the file.
 */
fun elfCheck(name: String, b: ByteArray): ElfVerdict? {
    if (!name.endsWith(".elf", ignoreCase = true) && !(b.size >= 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte())) return null
    if (b.size < 64 || b[0] != 0x7F.toByte() || b[1] != 'E'.code.toByte() || b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()) {
        return ElfVerdict.NOT_ELF
    }
    if (b[4].toInt() != 2 || b[5].toInt() != 1) return ElfVerdict.NOT_64 // ELFCLASS64, little-endian
    val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    if (bb.getShort(18).toInt() and 0xFFFF != 0x3E) return ElfVerdict.WRONG_MACHINE // EM_X86_64
    val phoff = bb.getLong(32)
    val phentsize = bb.getShort(54).toInt() and 0xFFFF
    val phnum = bb.getShort(56).toInt() and 0xFFFF
    if (phoff < 0 || phoff + phentsize.toLong() * phnum > b.size) return ElfVerdict.TRUNCATED
    // Each loadable part must lie inside the file
    for (i in 0 until phnum) {
        val at = (phoff + i.toLong() * phentsize).toInt()
        if (at + 40 > b.size) return ElfVerdict.TRUNCATED
        val offset = bb.getLong(at + 8)
        val fileSize = bb.getLong(at + 32)
        if (bb.getInt(at) == 1 && (offset < 0 || offset + fileSize > b.size)) return ElfVerdict.TRUNCATED // PT_LOAD
    }
    return ElfVerdict.OK
}

/** The name a build is kept under on the shelf: "dev-" and the link's file name. */
fun devName(url: String): String {
    // Only the path counts (not the site's name): "https://example.com/" has no file name
    val path = url.substringAfter("://").substringAfter('/', "")
    val last = path.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
    val clean = last.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "payload.elf" }
    return "dev-$clean"
}

object DevMode {
    private const val KEY = "dev_url"
    const val LOOK_MS = 15_000L

    fun url(ctx: Context): String = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    fun setUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, url.trim()).apply()
    }

    /** The build at [url] (null when it can't be read or is too big). Blocking. */
    fun fetch(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Crimson-Multitool-dev")
            if (c.responseCode !in 200..299) return null
            if (c.contentLengthLong > SHELF_MAX_BYTES) return null
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > SHELF_MAX_BYTES) return null
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    fun hash(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(12)
}

@Composable
fun DevModeDialog(console: Profile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(DevMode.url(ctx)) }
    var watching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var lastHash by remember { mutableStateOf("") }
    var builds by remember { mutableStateOf(0) }
    val port = console.payloadPort.trim().toIntOrNull()
    val canSend = console.host.isNotBlank() && port != null

    // While watching: look for a new build every 15 seconds; a new one is checked, kept and sent
    LaunchedEffect(watching, url) {
        while (watching && url.isNotBlank()) {
            val now = withContext(Dispatchers.IO) { try { DevMode.fetch(url) } catch (_: Exception) { null } }
            if (now == null) {
                status = AppText.get(R.string.dev_cant_read)
            } else {
                val h = DevMode.hash(now)
                if (h != lastHash) {
                    lastHash = h
                    val name = devName(url)
                    val verdict = elfCheck(name, now)
                    if (verdict != null && verdict != ElfVerdict.OK) {
                        status = AppText.get(R.string.dev_not_sent, AppText.get(verdict.res))
                    } else if (canSend) {
                        val reply = withContext(Dispatchers.IO) {
                            val f = File(shelfDir(ctx), name)
                            f.writeBytes(now)
                            sendPayloadReply(f, console.host.trim(), port!!)
                        }
                        builds++
                        if (reply.ok) notePayloadSent(console.host.trim(), name)
                        status = if (reply.ok) AppText.get(R.string.dev_sent, builds, h) else reply.text
                    }
                }
            }
            delay(DevMode.LOOK_MS)
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.dev_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.dev_intro, console.type.model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim(); DevMode.setUrl(ctx, url) },
                    singleLine = true,
                    enabled = !watching,
                    label = { Text(str(R.string.dev_url)) }
                )
                if (!canSend) Text(str(R.string.dev_need_console, console.type.model), style = MaterialTheme.typography.bodySmall)
                Button(enabled = canSend && url.startsWith("http"), onClick = {
                    watching = !watching
                    if (watching) {
                        lastHash = ""
                        status = AppText.get(R.string.dev_watching)
                    }
                }) { Text(str(if (watching) R.string.dev_stop else R.string.dev_start)) }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
                // The console log next to it: what the payload prints
                if (!KernelLog.running) {
                    OutlinedButton(enabled = console.host.isNotBlank(), onClick = {
                        KernelLog.attach(ctx)
                        KernelLog.start(console.host.trim(), console.name)
                    }) { Text(str(R.string.dev_start_log)) }
                } else {
                    Text(str(R.string.dev_log), style = MaterialTheme.typography.labelLarge)
                    Text(
                        KernelLog.lines.takeLast(30).joinToString("\n").ifEmpty { "…" },
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { watching = false; onClose() }) { Text(str(R.string.action_close)) } }
    )
}
