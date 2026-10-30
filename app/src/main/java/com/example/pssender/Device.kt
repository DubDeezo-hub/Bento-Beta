package com.example.pssender

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Small things the app asks the phone for: the clipboard, the app's own version, opening a link
// ---------------------------------------------------------------------------------------------

/** Puts [text] on the clipboard. When [doneMessage] is given it is shown as a short pop-up. */
fun copyText(ctx: Context, label: String, text: String, doneMessage: String? = null) {
    try {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (doneMessage != null) Toast.makeText(ctx, doneMessage, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
    }
}

/** The text on the clipboard, or an empty string when there is none (or the phone doesn't allow reading it). */
fun clipboardText(ctx: Context): String =
    try {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty() else ""
    } catch (_: Exception) {
        ""
    }

/** The version of this app as the phone knows it: its name ("1.23") and its number (23). */
class AppVersion(val name: String, val code: Long)

fun appVersion(ctx: Context): AppVersion =
    try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        AppVersion(info.versionName ?: "?", info.longVersionCode)
    } catch (_: Exception) {
        AppVersion("?", 0L)
    }

/** Opens the phone's share sheet with [text] under [title]; when nothing can share it, it is copied instead. */
fun shareText(ctx: Context, text: String, title: String, copiedMessage: String? = null) {
    try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        copyText(ctx, "Bento", text, copiedMessage)
    }
}

/** Opens a web address in the phone's browser. Returns false when nothing could open it. */
fun openLink(ctx: Context, url: String): Boolean =
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

/** "Pixel 7 · Android 14 (API 34) · en-US": what a bug report needs to know about the phone. */
fun phoneSummary(): String =
    "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
        Locale.getDefault().toLanguageTag()

/** The activity (screen) that [ctx] belongs to, or null when it is not inside one. */
fun findActivity(ctx: Context): Activity? {
    var c: Context? = ctx
    var guard = 0
    while (c != null && guard < 10) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
        guard++
    }
    return null
}
