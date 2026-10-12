package com.example.pssender // change to match your project's package name

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.apache.commons.net.ftp.FTPConnectionClosedException
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// ---------------------------------------------------------------------------------------------
// Errors that say what to do: a problem message gets a "What can I do?" button that opens a short
// explanation of the likely cause and the things to try.
//
// The messages themselves stay plain text. The place that creates a problem message knows what went wrong, so it
// notes the kind of problem for that text ([Hints.tag]); the screens ask [Hints.topicOf] with the text they show.
// ---------------------------------------------------------------------------------------------

/** The kinds of problem that have a help page. Each one names the texts of its title, its cause and its advice. */
enum class HelpTopic(val title: Int, val what: Int, val remedy: Int) {
    /** Nothing answers at the console's address. */
    UNREACHABLE(R.string.help_unreachable_title, R.string.help_unreachable_what, R.string.help_unreachable_try),

    /** The console answers, but the service that was asked for isn't running (or uses another port). */
    PORT_CLOSED(R.string.help_port_title, R.string.help_port_what, R.string.help_port_try),

    /** The FTP server turned the login down. */
    FTP_LOGIN(R.string.help_login_title, R.string.help_login_what, R.string.help_login_try),

    /** The connection broke, or stopped moving data. */
    DROPPED(R.string.help_dropped_title, R.string.help_dropped_what, R.string.help_dropped_try),

    /** The installer is there but didn't take the link. */
    INSTALL_REFUSED(R.string.help_refused_title, R.string.help_refused_what, R.string.help_refused_try),

    /** The console stored less than it was sent: a full or read-only drive. */
    CONSOLE_STORAGE(R.string.help_storage_title, R.string.help_storage_what, R.string.help_storage_try),

    /** The FTP server won't open, write or change that folder or file. */
    FTP_DENIED(R.string.help_denied_title, R.string.help_denied_what, R.string.help_denied_try),

    /** A file on the phone can't be read or written. */
    PHONE_FILE(R.string.help_phone_title, R.string.help_phone_what, R.string.help_phone_try),

    /** The website behind a download link didn't hand the file over. */
    DOWNLOAD_LINK(R.string.help_link_title, R.string.help_link_what, R.string.help_link_try)
}

/** Remembers which kind of problem the recent problem messages describe. */
object Hints {
    /** A text shorter than this is too generic to tell anything from. */
    private const val MIN_TEXT = 12

    /** How many messages are remembered. The oldest are forgotten first. */
    private const val KEEP = 200

    private val seen = LinkedHashMap<String, HelpTopic>()

    /** Notes that [text] describes a [topic] problem (nothing is noted for a null topic). Returns [text] itself. */
    fun tag(text: String, topic: HelpTopic?): String {
        if (topic == null || text.length < MIN_TEXT) return text
        synchronized(seen) {
            seen.remove(text)
            seen[text] = topic
            while (seen.size > KEEP) {
                val oldest = seen.keys.iterator().next()
                seen.remove(oldest)
            }
        }
        return text
    }

    /**
     * The kind of problem [text] describes, or null when it isn't a message that was noted. A message may have been
     * put inside a longer one ("Sending x: Failed: ..."), so a noted message found inside [text] counts too; the
     * longest one wins.
     */
    fun topicOf(text: String): HelpTopic? {
        if (text.length < MIN_TEXT) return null
        synchronized(seen) {
            val exact = seen[text]
            if (exact != null) return exact
            var found: HelpTopic? = null
            var foundLength = 0
            for ((known, topic) in seen) {
                if (known.length > foundLength && text.contains(known)) {
                    found = topic
                    foundLength = known.length
                }
            }
            return found
        }
    }

    /** Forgets everything (for the tests). */
    fun clear() {
        synchronized(seen) { seen.clear() }
    }
}

/**
 * The kind of problem behind [e]: what the network said when it failed. Null when it says nothing clear (the message
 * is shown without help then). The same error can come from any of the console's services, so the advice for a
 * closed port covers all of them.
 */
fun topicFor(e: Throwable): HelpTopic? {
    var t: Throwable? = e
    var guard = 0
    while (t != null && guard < 10) {
        val text = (t.message ?: "").lowercase()
        when {
            t is UnknownHostException || t is NoRouteToHostException -> return HelpTopic.UNREACHABLE
            t is ConnectException -> return if ("refused" in text) HelpTopic.PORT_CLOSED else HelpTopic.UNREACHABLE
            t is SocketTimeoutException -> return if ("connect" in text) HelpTopic.UNREACHABLE else HelpTopic.DROPPED
            t is FTPConnectionClosedException || t is EOFException -> return HelpTopic.DROPPED
            t is SocketException -> return when {
                "refused" in text -> HelpTopic.PORT_CLOSED
                "unreachable" in text || "no route" in text -> HelpTopic.UNREACHABLE
                else -> HelpTopic.DROPPED
            }
            "no space left" in text || "enospc" in text || t is FileNotFoundException -> return HelpTopic.PHONE_FILE
        }
        t = t.cause
        guard++
    }
    return null
}

/** The kind of problem behind an FTP answer code, or null for the codes that say nothing useful. */
fun topicForFtpReply(code: Int): HelpTopic? = when (code) {
    530, 332, 532 -> HelpTopic.FTP_LOGIN
    550, 553, 450, 551 -> HelpTopic.FTP_DENIED
    552, 452 -> HelpTopic.CONSOLE_STORAGE
    421 -> HelpTopic.DROPPED
    else -> null
}

/** The kind of problem behind an answer to a web request (a download link), or null when it isn't a problem. */
fun topicForHttp(code: Int): HelpTopic? = if (code >= 400) HelpTopic.DOWNLOAD_LINK else null

// ---------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------

/**
 * The advice for [topic] on a console of [type]. A PS4 has its own advice for a service that isn't running (GoldHEN brings
 * the loader and FTP, and the installer is a separate app); the other pages name the console and its HEN through %1$s and
 * %2$s (see [HelpDialog]).
 */
fun remedyFor(topic: HelpTopic, type: ConsoleType): Int =
    if (type == ConsoleType.PS4 && topic == HelpTopic.PORT_CLOSED) R.string.help_port_try_ps4 else topic.remedy

/** The explanation of one kind of problem. [details] is the message as it was shown, which can be copied. */
@Composable
fun HelpDialog(topic: HelpTopic, details: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val type = ActiveConsole.type
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(topic.title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(str(R.string.help_what), style = MaterialTheme.typography.labelLarge)
                Text(str(topic.what))
                Text(str(R.string.help_try), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                // The advice may name the console (%1$s, "PS5") and its homebrew enabler (%2$s, "etaHEN")
                Text(str(remedyFor(topic, type), type.model, type.hen))
                Text(str(R.string.help_details), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // When nothing answers at the console's address, the address may have changed: offer to look for it
                if (topic == HelpTopic.UNREACHABLE) {
                    TextButton(
                        onClick = {
                            FindConsole.ask()
                            onClose()
                        },
                        modifier = Modifier.padding(top = 6.dp)
                    ) { Text(str(R.string.find_button, type.model)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } },
        dismissButton = {
            TextButton(onClick = { copyText(ctx, "Bento", details, AppText.get(R.string.help_copied)) }) {
                Text(str(R.string.help_copy))
            }
        }
    )
}

/**
 * A "What can I do?" button for the problem message [message]. It shows nothing when the message isn't one that has a
 * help page, so it can be put next to any message text.
 */
@Composable
fun HelpLink(message: String, modifier: Modifier = Modifier) {
    val topic = Hints.topicOf(message) ?: return
    var open by remember(message) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) { Text(str(R.string.help_button)) }
    if (open) HelpDialog(topic, message) { open = false }
}
