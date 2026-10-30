package com.example.pssender

import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

// ---------------------------------------------------------------------------------------------
// The web link of a relay, as a source the transfer engine can continue after a break.
// ---------------------------------------------------------------------------------------------

/** One HTTP answer, reduced to what the relay needs. */
class HttpReply(
    val code: Int,
    private val headerOf: (String) -> String?,
    /** Length of the body as announced, or -1 when it isn't. */
    val bodyLength: Long,
    val body: InputStream?,
    private val onClose: () -> Unit
) : Closeable {
    fun header(name: String): String? = headerOf(name)

    override fun close() {
        try {
            onClose()
        } catch (_: Exception) {
        }
    }
}

/** Fetches web addresses. The app uses OkHttp; the tests use the JDK's own client. */
interface HttpFetcher {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpReply

    /** Ends the request that is in progress. */
    fun abort()
}

/** The part of a Content-Range header that matters: where it starts and how long the whole file is (-1 when unknown). */
class ContentRange(val start: Long, val total: Long)

/** "bytes 100-199/1000" gives start 100 and total 1000 (a total of "*" gives -1). Null when it can't be read. */
fun parseContentRange(header: String?): ContentRange? {
    if (header == null) return null
    val m = Regex("""^\s*bytes\s+(?:(\d+)-(\d+)|\*)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(header) ?: return null
    val start = m.groupValues[1].toLongOrNull() ?: -1L
    val total = m.groupValues[3].toLongOrNull() ?: -1L
    return ContentRange(start, total)
}

/** What identifies the file in an answer: a strong ETag if there is one, otherwise the modification time. Null when neither is given. */
private fun validatorOf(r: HttpReply): String? {
    val etag = r.header("ETag")
    return if (etag != null && !etag.startsWith("W/")) etag else r.header("Last-Modified")
}

/**
 * Hands out the body of an answer, and closes the answer together with it. A body that ends before the [expected] number
 * of bytes is an error, not a short file (a cut connection must never look like a finished download). [expected] is -1
 * when the length isn't known.
 */
private class ReplyStream(inner: InputStream, private val reply: HttpReply, private val expected: Long) : FilterInputStream(inner) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++ else checkEnd()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n else if (n < 0) checkEnd()
        return n
    }

    private fun checkEnd() {
        if (expected >= 0L && count != expected) throw IOException(Hints.tag(AppText.get(R.string.dl_cut_short), HelpTopic.DROPPED))
    }

    override fun close() {
        try {
            super.close()
        } finally {
            reply.close()
        }
    }
}

/**
 * A web link as a [ResumableSource]. The first answer says how long the file is and how to recognize it again. After
 * a break the next request asks only for the rest of the file (Range, with If-Range so a changed file is not mixed
 * up with the old one). A server that ignores Range gets the whole answer read and the first part dropped. When
 * neither works, the file is taken from the start.
 */
class HttpSource(
    private val url: String,
    private val userAgent: String,
    private val cookie: String?,
    private val fetcher: HttpFetcher
) : ResumableSource {
    private var total = -1L
    private var validator: String? = null
    private var reply: HttpReply? = null

    override fun open(offset: Long): OpenedSource {
        closeReply()
        // Without a known length a changed file couldn't be told from the old one, so there is no continuing
        val wanted = if (offset > 0L && total >= 0L) offset else 0L
        val r = request(wanted)
        reply = r
        val code = r.code
        if (code == 206 && wanted > 0L) {
            val range = parseContentRange(r.header("Content-Range"))
            val body = r.body
            if (body != null && range != null && range.start == wanted && (range.total < 0L || range.total == total) &&
                isSameFile(r, honoredIfRange = true)
            ) {
                return OpenedSource(ReplyStream(body, r, total - wanted), total, wanted)
            }
            return openFresh() // not the part that was asked for, or the file is not the same any more
        }
        if (code == 416 && wanted > 0L) {
            // "Range not satisfiable": the file is not longer than what we have, or it changed
            val range = parseContentRange(r.header("Content-Range"))
            if (range != null && range.total == wanted && wanted == total) {
                closeReply()
                return OpenedSource(EmptyStream(), total, wanted) // nothing left to fetch
            }
            return openFresh()
        }
        if (code == 200) {
            val body = r.body
            if (body == null) {
                closeReply()
                throw TransferException(Hints.tag(AppText.get(R.string.dl_empty), HelpTopic.DOWNLOAD_LINK), false)
            }
            if (wanted == 0L) {
                remember(r)
                return OpenedSource(ReplyStream(body, r, r.bodyLength), r.bodyLength, 0L)
            }
            // A part was asked for but the whole file came: the server doesn't do ranges, or the file is another one now
            if (r.bodyLength >= 0L && r.bodyLength == total && isSameFile(r, honoredIfRange = false)) {
                if (skipTo(body, wanted)) return OpenedSource(ReplyStream(body, r, total - wanted), total, wanted)
                return openFresh() // the data ended before the point we wanted
            }
            remember(r)
            return OpenedSource(ReplyStream(body, r, r.bodyLength), r.bodyLength, 0L)
        }
        closeReply()
        throw TransferException(
            Hints.tag(AppText.get(R.string.dl_http_failed, code.toString()), topicForHttp(code)), isTransientHttp(code)
        )
    }

    override fun abort() {
        fetcher.abort()
    }

    /** Asks for the whole file, with no Range. */
    private fun openFresh(): OpenedSource {
        closeReply()
        val r = request(0L)
        reply = r
        val body = r.body
        if (r.code != 200 || body == null) {
            val code = r.code
            closeReply()
            if (code == 200) throw TransferException(Hints.tag(AppText.get(R.string.dl_empty), HelpTopic.DOWNLOAD_LINK), false)
            throw TransferException(
                Hints.tag(AppText.get(R.string.dl_http_failed, code.toString()), topicForHttp(code)), isTransientHttp(code)
            )
        }
        remember(r)
        return OpenedSource(ReplyStream(body, r, r.bodyLength), r.bodyLength, 0L)
    }

    private fun request(from: Long): HttpReply {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = userAgent
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie
        if (from > 0L) {
            headers["Range"] = "bytes=$from-"
            validator?.let { headers["If-Range"] = it }
        }
        try {
            return fetcher.get(url, headers)
        } catch (e: IOException) {
            // The website could not be reached (no such host, refused, timed out). Whatever the network says, the advice is
            // about the link, not about the console. Whether another try may help is judged by the error itself.
            val cause = rootError(e)
            throw TransferException(
                Hints.tag(AppText.get(R.string.err_failed, cause.message ?: cause.javaClass.simpleName), HelpTopic.DOWNLOAD_LINK),
                isTransientFailure(e)
            )
        }
    }

    /** Keeps what identifies the file in this answer. */
    private fun remember(r: HttpReply) {
        total = r.bodyLength
        validator = validatorOf(r)
    }

    /**
     * True when the answer [r] is for the same file as the one seen before. Both sides having a validator, they must
     * match. A server that answered a ranged request with 206 ([honoredIfRange]) has vouched for it itself by not
     * ignoring our If-Range. With nothing to compare, the length (checked by the caller) is all there is.
     */
    private fun isSameFile(r: HttpReply, honoredIfRange: Boolean): Boolean {
        val before = validator
        val now = validatorOf(r)
        if (before == null && now == null) return true
        if (before != null && now != null) return before == now
        return honoredIfRange && before != null
    }

    private fun closeReply() {
        reply?.close()
        reply = null
    }
}

/** A stream with nothing in it. */
private class EmptyStream : InputStream() {
    override fun read(): Int = -1
}

/**
 * The web source of the transfer whose memory is [mem]: the same object for every try of that transfer (also after
 * "Try again"), so it keeps what it learned about the file and a try can continue where the last one stopped.
 */
fun httpSourceFor(mem: ResumeMemory, make: () -> HttpSource): HttpSource =
    mem.source as? HttpSource ?: make().also { mem.source = it }
