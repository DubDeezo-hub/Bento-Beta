package com.example.pssender // change to match your project's package name

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------------------------------------
// Transfers that were not finished when the app closed. What is queued (and what is running, and what failed and waits for
// a decision) is kept in a small file in the app's private storage, and offered again at the next start.
// The password and the cookie are left out on purpose: the app never saves the password anywhere, and a cookie is read from
// the browser again when the transfer is started.
// ---------------------------------------------------------------------------------------------

/** The most transfers that are kept. */
const val UNFINISHED_MAX = 200

/** The list of unfinished transfers as text, and back. */
object UnfinishedCodec {
    private const val VERSION = 1

    /** The text for [list] (at most [UNFINISHED_MAX] transfers). Neither the password nor the cookie is in it. */
    fun encode(list: List<TransferRequest>): String {
        val items = JSONArray()
        for (r in list.take(UNFINISHED_MAX)) {
            val o = JSONObject()
            o.put("name", r.name)
            o.put("host", r.host)
            o.put("port", r.port)
            o.put("user", r.user)
            o.put("dir", r.dir)
            r.fileUri?.let { o.put("fileUri", it) }
            r.url?.let { o.put("url", it) }
            r.userAgent?.let { o.put("userAgent", it) }
            r.remotePath?.let { o.put("remotePath", it) }
            o.put("remoteSize", r.remoteSize)
            o.put("console", r.console)
            o.put("profile", r.profileId)
            o.put("model", r.model)
            items.put(o)
        }
        return JSONObject().put("v", VERSION).put("items", items).toString()
    }

    /**
     * The transfers in [text]. Text that is not from this app, or from a version that is not known, gives an empty list;
     * a transfer that is incomplete (no name, no address, no port, or not exactly one of file, link and console file to
     * work on) is left out and the others are kept.
     */
    fun decode(text: String): List<TransferRequest> =
        try {
            val root = JSONObject(text)
            val items = root.optJSONArray("items")
            if (root.optInt("v", 0) != VERSION || items == null) {
                emptyList()
            } else {
                val found = ArrayList<TransferRequest>()
                for (i in 0 until items.length()) {
                    if (found.size >= UNFINISHED_MAX) break
                    items.optJSONObject(i)?.let { readOne(it) }?.let { found.add(it) }
                }
                found
            }
        } catch (_: Exception) {
            emptyList()
        }

    private fun readOne(o: JSONObject): TransferRequest? {
        fun text(key: String): String? = if (o.isNull(key)) null else o.optString(key, "")
        val name = text("name")?.takeIf { it.isNotBlank() } ?: return null
        val host = text("host")?.takeIf { it.isNotBlank() } ?: return null
        val port = o.optInt("port", 0)
        if (port !in 1..65535) return null
        val fileUri = text("fileUri")?.takeIf { it.isNotEmpty() }
        val url = text("url")?.takeIf { it.isNotEmpty() }
        val remotePath = text("remotePath")?.takeIf { it.isNotEmpty() }
        if (listOfNotNull(fileUri, url, remotePath).size != 1) return null
        return TransferRequest(
            name = name,
            host = host,
            port = port,
            user = text("user").orEmpty(),
            pass = "",
            dir = text("dir").orEmpty(),
            fileUri = fileUri,
            url = url,
            userAgent = text("userAgent"),
            cookie = null,
            remotePath = remotePath,
            remoteSize = o.optLong("remoteSize", -1L),
            console = text("console").orEmpty(),
            profileId = text("profile").orEmpty(),
            // Kept before there were PS4s: those were all PS5s
            model = text("model")?.takeIf { it == ConsoleType.PS4.model } ?: ConsoleType.PS5.model
        )
    }
}

/** Keeps a list of unfinished transfers somewhere. */
interface UnfinishedSaver {
    fun save(list: List<TransferRequest>)
}

/** Hears about it each time the list of unfinished transfers changes. */
interface TransferJournal {
    fun record(unfinished: List<TransferRequest>)
}

/** The list in a file. Nothing here may stop the app: a file that can't be written or read is the same as no file. */
class UnfinishedFile(private val file: File) : UnfinishedSaver {
    /** What was kept; empty when there is no file, or it can't be read or understood. */
    fun load(): List<TransferRequest> =
        try {
            if (file.isFile) UnfinishedCodec.decode(file.readText(Charsets.UTF_8)) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    /** Keeps [list] in place of what was kept before. An empty list removes the file. The file is replaced in one step, so it is never half written. */
    override fun save(list: List<TransferRequest>) {
        try {
            if (list.isEmpty()) {
                file.delete()
                return
            }
            val dir = file.absoluteFile.parentFile
            dir?.mkdirs()
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(UnfinishedCodec.encode(list), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * Hands each list to [saver] on a background thread, so the screen never waits for the storage. When several lists come
 * while one is being written, only the newest is written after it.
 */
class FileJournal(private val saver: UnfinishedSaver) : TransferJournal {
    private val lock = Any()
    private var next: List<TransferRequest>? = null
    private var writing = false

    override fun record(unfinished: List<TransferRequest>) {
        synchronized(lock) {
            next = unfinished
            if (writing) return
            writing = true
        }
        val thread = Thread({ drain() }, "unfinished-journal")
        thread.isDaemon = true
        thread.start()
    }

    private fun drain() {
        while (true) {
            val list = synchronized(lock) {
                val n = next
                next = null
                if (n == null) writing = false
                n
            } ?: return
            try {
                saver.save(list)
            } catch (_: Exception) {
            }
        }
    }

    /** Waits until everything handed over so far is written, but not longer than [timeoutMs]. Returns false when that took longer. */
    fun flush(timeoutMs: Long): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            if (synchronized(lock) { !writing && next == null }) return true
            if (System.nanoTime() >= end) return false
            Thread.sleep(2)
        }
    }
}

/** What [TransferCenter.continueUnfinished] did: [queued] transfers went into the queue, [missing] could not (their file is gone), and [start] says whether the transfer service has to be started. */
class ContinueResult(val queued: Int, val missing: Int, val start: Boolean)

/** True when [a] and [b] ask for the same work. The address does not count: a console can get a new one in between. */
fun sameTransfer(a: TransferRequest, b: TransferRequest): Boolean =
    a.name == b.name && a.dir == b.dir && a.fileUri == b.fileUri && a.url == b.url && a.remotePath == b.remotePath &&
        a.profileId.ifEmpty { a.host } == b.profileId.ifEmpty { b.host }

/**
 * Brings a transfer from the last run up to date before it starts again: the console may have a new address by now, the
 * password is the one typed in this session (it was never saved), and a link gets the cookie the browser has for it now.
 * [cookie] looks that up; it is only asked for links.
 */
fun refreshSaved(
    saved: TransferRequest,
    profiles: List<Profile>,
    passwords: Map<String, String>,
    cookie: (String) -> String?
): TransferRequest {
    val console = profiles.firstOrNull { saved.profileId.isNotEmpty() && it.id == saved.profileId }
        ?: profiles.firstOrNull { it.host.isNotBlank() && it.host.trim() == saved.host && it.ftpPort.trim().toIntOrNull() == saved.port }
    val updated = if (console == null) {
        saved
    } else {
        saved.copy(
            host = console.host.trim().ifEmpty { saved.host },
            port = console.ftpPort.trim().toIntOrNull() ?: saved.port,
            user = console.ftpUser.trim(),
            pass = passwords[console.id].orEmpty(),
            console = console.name,
            profileId = console.id,
            model = console.type.model
        )
    }
    val link = saved.url
    return if (link != null) updated.copy(cookie = cookie(link)) else updated
}

/** Up to [max] different names of [list], first ones first, for the card that offers the transfers again. [more] is true when there are more names than that. */
fun unfinishedNames(list: List<TransferRequest>, max: Int = 3): Pair<List<String>, Boolean> {
    val names = list.map { it.name }.distinct()
    return names.take(max) to (names.size > max)
}
