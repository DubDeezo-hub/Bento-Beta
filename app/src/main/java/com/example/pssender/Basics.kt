package com.example.pssender // change to match your project's package name

// Small helpers shared by the network code and the screens. No Android parts, so they can be tested on their own.

/** Human-readable size, e.g. 1.50 GB. */
fun fmtBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.2f GB".format(n / 1073741824.0)
    n >= 1L shl 20 -> "%.1f MB".format(n / 1048576.0)
    n >= 1L shl 10 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

/**
 * "Failed: <reason>" in the app's language. Falls back to the kind of error when it carries no message. When the error
 * says clearly what went wrong, the message gets a help page (see Help.kt).
 */
fun failedText(e: Exception): String =
    Hints.tag(AppText.get(R.string.err_failed, e.message ?: e.javaClass.simpleName), topicFor(e))

/** Where and how to log in to the console's FTP server. [model] is the kind of console ("PS5" or "PS4"), for the messages. */
data class FtpSettings(val host: String, val port: Int, val user: String, val pass: String, val model: String = "PS5")

/** Whether the app is on screen right now, for the parts that run in the background (the transfer notification stays quiet while you look at the app). */
object ScreenState {
    @Volatile
    var onScreen = false
}
