package com.example.pssender // change to match your project's package name

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPClientConfig
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.TimeUnit

private val http = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/** Display name of a file picked through the system file picker. */
fun ContentResolver.displayName(uri: Uri): String =
    query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    } ?: "file"

/** Size in bytes of a picked file, or -1 when the provider doesn't say. */
fun fileSize(cr: ContentResolver, uri: Uri): Long =
    cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L

/**
 * The exact size of a picked file: what the provider says, or else what the file itself says. -1 when neither is known
 * (a file that can't be shared, because the console has to be told its size before the download starts).
 */
fun exactSize(cr: ContentResolver, uri: Uri): Long {
    val asked = fileSize(cr, uri)
    if (asked > 0L) return asked
    return try {
        cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) {
        -1L
    }
}

/** A picked file as a source for the file server (see FileServer.kt). Each open gets its own handle on the file. */
class PhoneFileSource(private val cr: ContentResolver, private val uri: Uri) : ByteSource {
    override fun open(from: Long): InputStream {
        val handle = cr.openFileDescriptor(uri, "r") ?: throw IOException(phoneFileProblem())
        val input = ParcelFileDescriptor.AutoCloseInputStream(handle)
        try {
            seekOrSkip(input, from)
        } catch (e: Exception) {
            input.close()
            throw e
        }
        return input
    }
}

/**
 * Asks the console's package-installer service (port 12800: etaHEN's on a PS5, the Remote Package Installer app on a PS4)
 * to install a package from a URL.
 * 1) etaHEN style: multipart POST /upload with a "url" field; success text contains "SUCCESS:".
 * 2) Remote Package Installer style: POST /api/install with {"type":"direct","packages":["<link>"]}; the link goes as it
 *    is, the way flatz's README shows it, except that an https link is sent as http.
 * Formats are taken from public documentation of existing installer tools; verify against your console.
 */
suspend fun installRequest(host: String, port: String, pkgUrl: String): InstallReply =
    withContext(Dispatchers.IO) {
        val base = "http://$host:$port"
        try {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "", "".toRequestBody("application/octet-stream".toMediaType()))
                .addFormDataPart("url", pkgUrl)
                .build()
            val first = http.newCall(Request.Builder().url("$base/upload").post(form).build())
                .execute().use { it.code to it.body?.string().orEmpty() }
            if (first.second.contains("SUCCESS:")) {
                return@withContext InstallReply(true, AppText.get(R.string.install_accepted, first.second.trim()))
            }

            // RPI style: the plain link, with the https scheme swapped to http
            val link = if (pkgUrl.startsWith("https://", ignoreCase = true)) "http://" + pkgUrl.substring(8) else pkgUrl
            val json = "{\"type\":\"direct\",\"packages\":[" + jsonString(link) + "]}"
            val second = http.newCall(
                Request.Builder().url("$base/api/install")
                    .post(json.toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
            ).execute().use { it.code to it.body?.string().orEmpty() }
            if (second.second.contains("success")) {
                InstallReply(true, AppText.get(R.string.install_accepted, second.second.trim()))
            } else {
                InstallReply(
                    false,
                    Hints.tag(
                        AppText.get(
                            R.string.install_not_accepted,
                            first.first.toString(), first.second.take(80), second.first.toString(), second.second.take(80)
                        ),
                        HelpTopic.INSTALL_REFUSED
                    )
                )
            }
        } catch (e: Exception) {
            InstallReply(false, failedText(e))
        }
    }

/**
 * [s] as a JSON string. Only the quote, the backslash and control characters are escaped: a "/" stays as it is, the way
 * the Remote Package Installer's examples write links (a JSON library would send "\/").
 */
private fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}

/** Like [installRequest], but only returns the message to show. */
suspend fun sendInstall(host: String, port: String, pkgUrl: String): String = installRequest(host, port, pkgUrl).text

// ---------------------------------------------------------------------------------------------
// Payload sending
// ---------------------------------------------------------------------------------------------

/**
 * Blocking: streams [input] over TCP to a payload/ELF loader already listening on the console, then closes the
 * write side so the loader sees end-of-file. Returns "Sent 1.5 MB" (in the app's language).
 */
fun sendBytesToLoader(input: InputStream, host: String, port: Int): String =
    Socket().use { sock ->
        sock.connect(InetSocketAddress(host, port), 5000)
        val out = sock.getOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        out.flush()
        sock.shutdownOutput()
        AppText.get(R.string.payload_sent, fmtBytes(total))
    }

/** Sends a file picked with the system file picker to the payload loader. */
suspend fun sendPayload(cr: ContentResolver, uri: Uri, host: String, port: Int): String =
    withContext(Dispatchers.IO) {
        val input = cr.openInputStream(uri) ?: return@withContext phoneFileProblem()
        try {
            input.use { sendBytesToLoader(it, host, port) }
        } catch (e: Exception) {
            failedText(e)
        }
    }

/** What sending a payload gave: whether it worked, and the message to show ("Sent 1.5 MB", or what went wrong). */
class PayloadReply(val ok: Boolean, val text: String)

/** Sends a payload stored on the shelf (see Shelf.kt) to the payload loader. */
suspend fun sendPayloadReply(file: File, host: String, port: Int): PayloadReply =
    withContext(Dispatchers.IO) {
        try {
            PayloadReply(true, FileInputStream(file).use { sendBytesToLoader(it, host, port) })
        } catch (e: Exception) {
            PayloadReply(false, failedText(e))
        }
    }

/** Like [sendPayloadReply], but only returns the message to show. */
suspend fun sendPayloadFile(file: File, host: String, port: Int): String = sendPayloadReply(file, host, port).text

/**
 * Downloads [url] on the phone straight into the payload shelf. Returns a message for the status line.
 * [cookie] lets links that need a logged-in session work.
 */
suspend fun shelfAddFromUrl(ctx: Context, url: String, userAgent: String, cookie: String?, fileName: String): String =
    withContext(Dispatchers.IO) {
        try {
            val rb = Request.Builder().url(url).header("User-Agent", userAgent)
            if (!cookie.isNullOrEmpty()) rb.header("Cookie", cookie)
            http.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Hints.tag(AppText.get(R.string.dl_http_failed, resp.code.toString()), HelpTopic.DOWNLOAD_LINK)
                }
                val body = resp.body ?: return@withContext Hints.tag(AppText.get(R.string.dl_empty), HelpTopic.DOWNLOAD_LINK)
                val len = body.contentLength()
                if (len > SHELF_MAX_BYTES) {
                    return@withContext AppText.get(R.string.shelf_too_large_url, fmtBytes(len), fmtBytes(SHELF_MAX_BYTES))
                }
                shelfAddedMessage(body.byteStream().use { shelfAdd(shelfDir(ctx), fileName, it) })
            }
        } catch (e: ShelfTooLarge) {
            shelfTooLargeMessage(e.limit)
        } catch (e: Exception) {
            failedText(e)
        }
    }

// ---------------------------------------------------------------------------------------------
// Is a port open
// ---------------------------------------------------------------------------------------------

/** True when something accepts a TCP connection on [host]:[port]. It connects and closes; nothing is sent. */
suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int = 1500): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

// ---------------------------------------------------------------------------------------------
// FTP: settings, paths and shared plumbing
// ---------------------------------------------------------------------------------------------

/**
 * Message for a failed FTP command; adds advice about the FTP port when the console couldn't be reached at all
 * (the usual cause).
 */
fun ftpFailureMessage(e: Exception): String {
    if (e !is ConnectException && e !is SocketTimeoutException) return failedText(e)
    val hint = if (ActiveConsole.type == ConsoleType.PS4) R.string.ftp_port_hint_ps4 else R.string.ftp_port_hint
    return Hints.tag(failedText(e) + "\n" + AppText.get(hint), topicFor(e))
}

/** The message for a file problem on the phone ("Could not open the file"), with its help page. */
fun phoneFileProblem(): String = Hints.tag(AppText.get(R.string.err_open_file), HelpTopic.PHONE_FILE)

/**
 * A message built from the console's last FTP answer ([R.string.err_cant_open] and the like: the first value is [name] when
 * the text takes one, then the answer). A refusal gets the help page of the answer code, or the one for a refused folder.
 */
private fun deniedText(textId: Int, ftp: FTPClient, name: String? = null): String {
    val reply = ftp.replyString.trim()
    val text = if (name == null) AppText.get(textId, reply) else AppText.get(textId, name, reply)
    return Hints.tag(text, topicForFtpReply(ftp.replyCode) ?: HelpTopic.FTP_DENIED)
}

/** One row of a folder listing. [size] is in bytes (0 for folders). [modified] is when it last changed (milliseconds since 1970), or 0 when the listing doesn't say. */
data class FtpEntry(val name: String, val isDir: Boolean, val isLink: Boolean, val size: Long, val modified: Long = 0L)

/** A folder listing: the folder's path as the server reports it, and its entries (folders first). */
data class FtpListing(val path: String, val entries: List<FtpEntry>)

/** "/data" + "a.txt" -> "/data/a.txt" (never a doubled slash). */
fun joinPath(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

/** "/data/games" -> "/data", "/data" -> "/", "/" -> "/". */
fun parentPath(path: String): String {
    val t = path.trimEnd('/')
    val i = t.lastIndexOf('/')
    return if (i <= 0) "/" else t.substring(0, i)
}

/** Null when [name] is fine for a file or folder on the console, otherwise the reason it isn't. */
fun badRemoteName(name: String): String? = when {
    name.isBlank() -> AppText.get(R.string.name_empty)
    name == "." || name == ".." -> AppText.get(R.string.name_not_allowed)
    name.contains('/') || name.contains('\\') -> AppText.get(R.string.name_slash)
    name.any { it.isISOControl() } -> AppText.get(R.string.name_control)
    else -> null
}

/** Folders first, then everything else, each group in alphabetical order. */
fun sortEntries(entries: List<FtpEntry>): List<FtpEntry> =
    entries.sortedWith(compareBy<FtpEntry>({ !it.isDir }, { it.name.lowercase() }))

/**
 * Runs [block] on a freshly connected client and always disconnects afterwards. Meant for short commands
 * (list, rename, delete, ...), so it gives up on a console that stops answering instead of waiting forever.
 */
private fun <T> withFtp(s: FtpSettings, block: (FTPClient) -> T): T {
    val ftp = FTPClient()
    // Console FTP servers print folders in the usual Unix "ls -l" style; this also skips the SYST guessing
    ftp.configure(FTPClientConfig(FTPClientConfig.SYST_UNIX))
    ftp.setDefaultTimeout(30000)
    ftp.setDataTimeout(Duration.ofSeconds(30))
    try {
        ftpConnect(ftp, s)?.let { throw IOException(it) }
        return block(ftp)
    } finally {
        try {
            if (ftp.isConnected) {
                ftp.logout()
                ftp.disconnect()
            }
        } catch (_: Exception) {
        }
    }
}

// ---------------------------------------------------------------------------------------------
// FTP: browsing and file management
// ---------------------------------------------------------------------------------------------

private fun listFolder(ftp: FTPClient, path: String): FtpListing {
    if (!ftp.changeWorkingDirectory(path)) throw IOException(deniedText(R.string.err_cant_open, ftp, path))
    val here = ftp.printWorkingDirectory() ?: path
    val entries = ftp.listFiles().orEmpty().filterNotNull()
        .filter { !it.name.isNullOrEmpty() && it.name != "." && it.name != ".." }
        .map { f ->
            FtpEntry(
                name = f.name,
                isDir = f.isDirectory,
                isLink = f.isSymbolicLink,
                size = if (f.isDirectory) 0L else f.size.coerceAtLeast(0L),
                modified = f.timestamp?.timeInMillis ?: 0L
            )
        }
    return FtpListing(here, sortEntries(entries))
}

/** Lists the folder [path] on the console. Throws IOException with a readable message when it can't. */
fun ftpList(s: FtpSettings, path: String): FtpListing = withFtp(s) { ftp -> listFolder(ftp, path) }

/** Renames [from] to [to] inside the folder [dir]. */
fun ftpRename(s: FtpSettings, dir: String, from: String, to: String) {
    withFtp(s) { ftp ->
        if (!ftp.rename(joinPath(dir, from), joinPath(dir, to))) {
            throw IOException(deniedText(R.string.err_rename, ftp))
        }
    }
}

/** Creates the folder [name] inside [dir]. */
fun ftpMakeDir(s: FtpSettings, dir: String, name: String) {
    withFtp(s) { ftp ->
        if (!ftp.makeDirectory(joinPath(dir, name))) {
            throw IOException(deniedText(R.string.err_mkdir, ftp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// FTP: small reads and folder trees (installed games, saves, folder sync)
// ---------------------------------------------------------------------------------------------

/** Reads a small file from an open connection; null when it is missing, unreadable or larger than [max] bytes. */
private fun readSmall(ftp: FTPClient, path: String, max: Int): ByteArray? {
    ftp.setFileType(FTP.BINARY_FILE_TYPE)
    val input = ftp.retrieveFileStream(path) ?: return null
    val out = java.io.ByteArrayOutputStream()
    var tooBig = false
    try {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) {
                tooBig = true
                break
            }
        }
    } finally {
        try { input.close() } catch (_: Exception) {}
    }
    // Ends the transfer; when it was cut short the server may report an error, which is fine here
    try { ftp.completePendingCommand() } catch (_: Exception) {}
    return if (tooBig) null else out.toByteArray()
}

/** Reads the small file [path] on the console (null when it isn't there or is larger than [max] bytes). */
fun ftpReadSmall(s: FtpSettings, path: String, max: Int = 1 shl 20): ByteArray? = withFtp(s) { ftp -> readSmall(ftp, path, max) }

/**
 * Reads several small files over one connection. For each of [paths], [each] gets its bytes (null when it could not be
 * read). [stop] ends early (the screen was left).
 */
fun ftpReadEach(
    s: FtpSettings, paths: List<String>, max: Int = 1 shl 20,
    stop: () -> Boolean = { false }, each: (String, ByteArray?) -> Unit
) {
    withFtp(s) { ftp ->
        for (path in paths) {
            if (stop()) break
            val data = try { readSmall(ftp, path, max) } catch (e: IOException) {
                if (!ftp.isConnected) throw e
                null
            }
            each(path, data)
        }
    }
}

/** Lists [path] on the console; an empty list when it can't be opened (a folder that may not exist). */
fun ftpListOrEmpty(s: FtpSettings, path: String): List<FtpEntry> = withFtp(s) { ftp ->
    try { listFolder(ftp, path).entries } catch (_: IOException) { emptyList() }
}

/** Lists several folders over one connection; a folder that can't be opened gives an empty list. */
fun ftpListMany(s: FtpSettings, paths: List<String>): Map<String, List<FtpEntry>> = withFtp(s) { ftp ->
    paths.associateWith { p -> try { listFolder(ftp, p).entries } catch (_: IOException) { emptyList() } }
}

/**
 * Every file under [dir] on the console, by its path relative to [dir] ("a/b.bin"), with its size. Folders that can't be
 * opened are left out; links are not followed. Gives up after [maxFiles] files (then [RemoteTree.truncated] is set).
 */
class RemoteTree(val files: Map<String, Long>, val folders: Set<String>, val exists: Boolean, val truncated: Boolean)

fun ftpTree(s: FtpSettings, dir: String, maxFiles: Int = 20_000): RemoteTree = withFtp(s) { ftp ->
    val files = HashMap<String, Long>()
    val folders = HashSet<String>()
    var truncated = false
    val top = try { listFolder(ftp, dir).entries } catch (_: IOException) { return@withFtp RemoteTree(emptyMap(), emptySet(), false, false) }
    fun walk(entries: List<FtpEntry>, rel: String, depth: Int) {
        for (e in entries) {
            if (files.size >= maxFiles) { truncated = true; return }
            val r = if (rel.isEmpty()) e.name else "$rel/${e.name}"
            when {
                e.isLink -> {}
                e.isDir -> {
                    folders.add(r)
                    if (depth < 16) {
                        val inner = try { listFolder(ftp, joinPath(dir, r)).entries } catch (_: IOException) { emptyList() }
                        walk(inner, r, depth + 1)
                    }
                }
                else -> files[r] = e.size
            }
        }
    }
    walk(top, "", 0)
    RemoteTree(files, folders, true, truncated)
}

/**
 * Creates [dir] and the folders [relDirs] inside it (paths relative to [dir], parents before children), skipping the ones
 * in [existing]. Returns how many were made.
 */
fun ftpMakeDirs(s: FtpSettings, dir: String, relDirs: List<String>, existing: Set<String> = emptySet(), makeTop: Boolean = false): Int =
    withFtp(s) { ftp ->
        var made = 0
        if (makeTop) {
            // Each step of the path, so a deep new folder works too
            var at = ""
            for (part in dir.split('/').filter { it.isNotEmpty() }) {
                at += "/$part"
                if (!ftp.changeWorkingDirectory(at)) {
                    if (!ftp.makeDirectory(at)) throw IOException(deniedText(R.string.err_mkdir, ftp))
                    made++
                }
            }
        }
        for (rel in relDirs.sortedBy { it.count { c -> c == '/' } }) {
            if (rel in existing) continue
            val full = joinPath(dir, rel)
            if (!ftp.changeWorkingDirectory(full)) {
                if (!ftp.makeDirectory(full)) throw IOException(deniedText(R.string.err_mkdir, ftp))
                made++
            }
        }
        made
    }

/** Deletes a folder with everything inside it. Links are removed, never followed. Returns the number of items removed. */
private fun deleteTree(ftp: FTPClient, path: String, depth: Int): Int {
    if (depth > 40) throw IOException(AppText.get(R.string.err_too_deep))
    var removed = 0
    for (e in listFolder(ftp, path).entries) {
        val child = joinPath(path, e.name)
        if (e.isDir) {
            removed += deleteTree(ftp, child, depth + 1)
        } else {
            if (!ftp.deleteFile(child)) throw IOException(deniedText(R.string.err_delete_path, ftp, child))
            removed++
        }
    }
    // Step out first: some servers refuse to remove the folder they are currently inside
    ftp.changeWorkingDirectory("/")
    if (!ftp.removeDirectory(path)) throw IOException(deniedText(R.string.err_delete_path, ftp, path))
    return removed + 1
}

/** Deletes [entry] from the folder [dir] on a client that is connected. Returns how many items were removed. */
private fun deleteOne(ftp: FTPClient, dir: String, entry: FtpEntry): Int {
    val full = joinPath(dir, entry.name)
    return if (entry.isDir) {
        deleteTree(ftp, full, 0)
    } else {
        if (!ftp.deleteFile(full)) throw IOException(deniedText(R.string.err_delete_path, ftp, entry.name))
        1
    }
}

/** Deletes [entry] from the folder [dir]; a folder goes with everything inside it. Returns how many items were removed. */
fun ftpDelete(s: FtpSettings, dir: String, entry: FtpEntry): Int = withFtp(s) { ftp -> deleteOne(ftp, dir, entry) }

/**
 * Deletes each of [entries] from the folder [dir] in one go (one connection), folders with everything inside them. One that
 * can't be deleted is counted and the rest are still tried (see [deleteEach]).
 */
fun ftpDeleteMany(s: FtpSettings, dir: String, entries: List<FtpEntry>): DeleteSummary =
    withFtp(s) { ftp -> deleteEach(entries) { e -> deleteOne(ftp, dir, e) } }

/**
 * What downloading the [chosen] entries of the folder [dir] comes to (see [planDownload]). One connection looks through all
 * the folders. Throws when the console can't be reached or a folder can't be opened.
 */
fun ftpPlanDownload(s: FtpSettings, dir: String, chosen: List<FtpEntry>): DownloadPlan =
    withFtp(s) { ftp -> planDownload(dir, chosen, { path -> listFolder(ftp, path).entries }) }

// ---------------------------------------------------------------------------------------------
// FTP transfers (the engine is in Engine.kt; this is what ties it to the phone)
// ---------------------------------------------------------------------------------------------

/**
 * Keeps the right to read a picked file after the screen that picked it is gone. Without this, Android takes the right
 * back when the app's screen is closed, and a file that still waits in the queue (or has to be opened again after a
 * dropped connection) could not be read any more.
 */
fun keepAccess(cr: ContentResolver, uri: Uri) {
    try {
        cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {
        // The provider does not offer lasting access: the right it gave for the life of the screen has to do
    }
}

/**
 * True when a picked file can still be read: the right to read it may have been taken back, or the file deleted, since the
 * transfer was queued. May take a moment for a file that lives in the cloud, so it is not for the main thread.
 */
fun canReadPicked(cr: ContentResolver, uri: Uri): Boolean =
    try {
        cr.openInputStream(uri)?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

/** A file picked with the system file picker, as something an upload can read and read again from the middle. */
private class ContentUriSource(private val cr: ContentResolver, private val uri: Uri) : ResumableSource {
    private fun openStream(): InputStream =
        cr.openInputStream(uri) ?: throw TransferException(phoneFileProblem(), false)

    override fun open(offset: Long): OpenedSource {
        val total = fileSize(cr, uri)
        val input = openStream()
        if (offset <= 0L) return OpenedSource(input, total, 0L)
        if (skipTo(input, offset)) return OpenedSource(input, total, offset)
        // the file is shorter than expected: read it from the start
        input.close()
        return OpenedSource(openStream(), total, 0L)
    }
}

/** Uploads a picked file to [remoteDir]/[name] on the console's FTP server. */
fun ftpUploadPicked(
    cr: ContentResolver, uri: Uri, name: String, s: FtpSettings, remoteDir: String,
    mem: ResumeMemory, hooks: TransferHooks
): TransferResult = ftpUpload(ContentUriSource(cr, uri), name, s, remoteDir, mem, hooks)

/** Asks OkHttp for web addresses on behalf of the relay. */
private class OkHttpFetcher(private val client: OkHttpClient) : HttpFetcher {
    @Volatile
    private var call: Call? = null

    override fun get(url: String, headers: Map<String, String>): HttpReply {
        val rb = Request.Builder().url(url)
        for ((name, value) in headers) rb.header(name, value)
        val c = client.newCall(rb.build())
        call = c
        val resp = c.execute()
        val body = resp.body
        return HttpReply(
            resp.code,
            { name -> resp.header(name) },
            body?.contentLength() ?: -1L,
            body?.byteStream(),
            { resp.close() }
        )
    }

    override fun abort() {
        call?.cancel()
    }
}

/**
 * Downloads [url] on the phone and streams it straight to the console's FTP server, without saving a copy
 * on the phone. [cookie] lets links that need a logged-in session work.
 */
fun ftpUploadFromUrl(
    url: String, userAgent: String, cookie: String?, fileName: String,
    s: FtpSettings, remoteDir: String, mem: ResumeMemory, hooks: TransferHooks
): TransferResult {
    val source = httpSourceFor(mem) { HttpSource(url, userAgent, cookie, OkHttpFetcher(http)) }
    return ftpUpload(source, fileName, s, remoteDir, mem, hooks)
}

/** The half-written (or finished) download in the phone's Downloads/PS5 folder: a MediaStore entry, so no storage permission is needed. */
private class MediaStoreSink(private val cr: ContentResolver, private val uri: Uri) : ResumableSink {
    override fun storedLength(): Long =
        try {
            cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) {
            0L
        }

    override fun open(offset: Long): OutputStream {
        val pfd = cr.openFileDescriptor(uri, "rw")
            ?: throw TransferException(Hints.tag(AppText.get(R.string.dl_write_failed), HelpTopic.PHONE_FILE), false)
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        try {
            val channel = out.channel
            channel.truncate(offset) // drops what an earlier try wrote beyond the point we continue from
            channel.position(offset)
        } catch (e: IOException) {
            out.close()
            throw e
        }
        return out
    }

    /** Makes the finished file visible to the other apps. */
    fun publish() {
        try {
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (_: Exception) {
        }
    }

    /** Removes the file. */
    fun delete() {
        try {
            cr.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}

/**
 * Downloads [remote] from the console into the phone's Downloads/PS5 folder (or into the folders [saveIn] names inside it:
 * "Games/Sub" for Downloads/PS5/Games/Sub), and carries on after a dropped connection. A cancelled or failed-for-good
 * download leaves nothing behind; one that gave up on a bad connection keeps its first part until the transfer is tried
 * again or dropped (see [ResumeMemory]).
 */
fun ftpDownloadToPhone(
    cr: ContentResolver, remote: String, name: String, size: Long, s: FtpSettings,
    mem: ResumeMemory, hooks: TransferHooks, saveIn: String = ""
): TransferResult {
    var sink = mem.sink as? MediaStoreSink
    if (sink == null) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, downloadFolder(saveIn, s.model))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return TransferResult(false, Hints.tag(AppText.get(R.string.dl_create_failed), HelpTopic.PHONE_FILE))
        val created = MediaStoreSink(cr, uri)
        mem.sink = created
        mem.discard = { created.delete() }
        sink = created
    }
    val result = ftpDownload(sink, remote, size, s, AppText.get(R.string.saved_to_downloads, savedAs(saveIn, name), s.model), mem, hooks)
    if (result.ok) {
        sink.publish()
        mem.sink = null
        mem.discard = null
    } else if (leavesNothingToKeep(result)) {
        // (a paused download keeps its first part: running it again continues from there)
        sink.delete()
        mem.sink = null
        mem.discard = null
    }
    return result
}

// ---------------------------------------------------------------------------------------------
// Reading parts of a package (its name and icon) without downloading all of it
// ---------------------------------------------------------------------------------------------

/** Reads pieces of a web link with Range requests. Refuses servers that only send whole files (packages are huge). */
class HttpRangeReader(private val url: String) : RangeReader {
    private var known = -1L
    override val size: Long get() = known

    override fun read(offset: Long, length: Int): ByteArray {
        val req = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty reply")
            if (resp.code == 206) {
                resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { known = it }
            } else {
                // The whole file is coming: fine for its start, not for anything further in
                if (known < 0L) known = body.contentLength()
                if (offset > 0L) throw IOException(AppText.get(R.string.meta_no_ranges))
            }
            val out = ByteArray(length)
            var got = 0
            val input = body.byteStream()
            while (got < length) {
                val n = input.read(out, got, length - got)
                if (n < 0) break
                got += n
            }
            return if (got == length) out else out.copyOf(got)
        }
    }
}

/** Reads pieces of a file on the phone. */
class UriRangeReader(private val cr: ContentResolver, private val uri: Uri) : RangeReader {
    override val size: Long = fileSize(cr, uri)

    override fun read(offset: Long, length: Int): ByteArray {
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException(phoneFileProblem())
        pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val ch = input.channel
                ch.position(offset)
                val buf = java.nio.ByteBuffer.allocate(length)
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) break
                }
                return buf.array().copyOf(buf.position())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A folder on the phone, picked with the system's folder picker
// ---------------------------------------------------------------------------------------------

/** Reads the picked folder [tree] all the way down (at most [maxFiles] files). Not for the main thread. */
fun walkPhoneTree(cr: ContentResolver, tree: Uri, maxFiles: Int = 20_000): PhoneTree {
    val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
    val files = ArrayList<PhoneFile>()
    val folders = ArrayList<String>()
    var truncated = false
    val cols = arrayOf(
        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
        android.provider.DocumentsContract.Document.COLUMN_SIZE
    )
    var rootName = ""
    try {
        cr.query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootId), cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) rootName = c.getString(1).orEmpty()
        }
    } catch (_: Exception) {
    }
    fun walk(docId: String, rel: String, depth: Int) {
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val found = ArrayList<Triple<String, String, Pair<Boolean, Long>>>()
        cr.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                found.add(Triple(id, name, isDir to size))
            }
        }
        for ((id, name, info) in found) {
            if (files.size >= maxFiles) { truncated = true; return }
            val r = if (rel.isEmpty()) name else "$rel/$name"
            if (info.first) {
                folders.add(r)
                if (depth < 16) walk(id, r, depth + 1) else truncated = true
            } else {
                files.add(PhoneFile(r, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), info.second))
            }
        }
    }
    walk(rootId, "", 0)
    return PhoneTree(rootName, files, folders, truncated)
}

/** Keeps the right to read a picked folder after the screen that picked it is gone. */
fun keepTreeAccess(cr: ContentResolver, tree: Uri) {
    try {
        cr.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {
    }
}

/** Saves [text] as Downloads/<root>/<folder>/<name> on the phone. Gives where it went, or null when it couldn't. */
fun saveTextToDownloads(cr: ContentResolver, root: String, folder: String, name: String, text: String): String? =
    try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".json")) "application/json" else "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, downloadFolder(folder, root))
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) null else {
            cr.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IOException("no output")
            "Download/" + root + "/" + folder + "/" + name
        }
    } catch (_: Exception) {
        null
    }
