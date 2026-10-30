package com.example.pssender

// Small helpers shared by the network code and the screens. No Android parts, so they can be tested on their own.

/**
 * Human-readable size, e.g. 1.50 GB. A size that would show as "1024.0 MB" or "1024 KB" once rounded is shown in the
 * next unit up ("1.00 GB", "1.0 MB"), so the numbers never reach 1024.
 */
fun fmtBytes(n: Long): String {
    val kb = n / 1024.0
    val mb = n / 1048576.0
    return when {
        Math.round(mb * 10) >= 10240L -> "%.2f GB".format(n / 1073741824.0)
        Math.round(kb) >= 1024L -> "%.1f MB".format(mb)
        n >= 1L shl 10 -> "%.0f KB".format(kb)
        else -> "$n B"
    }
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
