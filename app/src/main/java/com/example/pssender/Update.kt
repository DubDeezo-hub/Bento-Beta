package com.example.pssender

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------------------------
// Update check: looks at the newest release on the project's GitHub Releases page and says when it is newer
// than the installed app. It only looks (two small web requests at most); installing is up to the person, who
// downloads the file and opens it, as with any app from outside the store.
// ---------------------------------------------------------------------------------------------

/** The repository whose Releases page holds the APK files that the build publishes. */
const val UPDATE_REPO = "DubDeezo-hub/Bento-Beta"

/** How long to wait between two automatic checks. */
const val UPDATE_EVERY_MS = 24L * 60L * 60L * 1000L

/**
 * A published version. [number] is the build number, which is also the app's version code: the part of the tag after
 * the last dot (tag "v1.23" is number 23). [apkUrl] is the direct link to the .apk when the release has one.
 */
data class ReleaseInfo(
    val tag: String,
    val number: Long,
    val pageUrl: String,
    val apkUrl: String?
) {
    /** "1.23": the tag without its leading v, as the app shows versions. */
    val versionName: String get() = tag.removePrefix("v").removePrefix("V")
}

/** What a check found. */
sealed class UpdateCheck {
    /** A version newer than the installed one is published. */
    class Newer(val release: ReleaseInfo) : UpdateCheck()

    /** The newest published version is the installed one (or older). */
    class UpToDate(val release: ReleaseInfo) : UpdateCheck()

    /** Nothing is published on the Releases page yet (or the page can't be seen without signing in). */
    object NoRelease : UpdateCheck()

    /** The check could not be done (no network, GitHub not answering). [reason] is the error text. */
    class Failed(val reason: String) : UpdateCheck()
}

/** The build number in a release tag: the last run of digits ("v1.23" is 23, "1.7" is 7). Null when there is none. */
fun releaseNumber(tag: String): Long? = Regex("""(\d+)(?!.*\d)""").find(tag)?.value?.toLongOrNull()

/** The release described by the answer of GitHub's "latest release" request, or null when it doesn't describe one. */
fun parseRelease(json: String): ReleaseInfo? {
    return try {
        val o = JSONObject(json)
        val tag = o.optString("tag_name", "")
        val number = releaseNumber(tag) ?: return null
        val page = o.optString("html_url", "").ifBlank { "https://github.com/$UPDATE_REPO/releases/tag/$tag" }
        var apk: String? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                val link = a.optString("browser_download_url", "")
                if (link.isNotEmpty() && name.endsWith(".apk", ignoreCase = true)) {
                    apk = link
                    break
                }
            }
        }
        ReleaseInfo(tag, number, page, apk)
    } catch (_: Exception) {
        null
    }
}

/** The release behind a tag, when only the tag is known: the build names its file Bento-<version>.apk. */
fun releaseFromTag(tag: String): ReleaseInfo? {
    val number = releaseNumber(tag) ?: return null
    val base = "https://github.com/$UPDATE_REPO/releases"
    val name = tag.removePrefix("v").removePrefix("V")
    return ReleaseInfo(tag, number, "$base/tag/$tag", "$base/download/$tag/Bento-$name.apk")
}

/** The tag in the address that the website's "latest" page sends you on to, or null when it is not a release page. */
fun tagFromRedirect(location: String?): String? {
    if (location == null) return null
    val marker = "/releases/tag/"
    val at = location.indexOf(marker)
    if (at < 0) return null
    val tag = location.substring(at + marker.length).substringBefore('?').substringBefore('#').trim('/')
    return tag.ifEmpty { null }
}

/** Compares the newest [release] with the installed version code. */
fun judgeRelease(release: ReleaseInfo, installedCode: Long): UpdateCheck =
    if (release.number > installedCode) UpdateCheck.Newer(release) else UpdateCheck.UpToDate(release)

/** True when an automatic check is due: it is switched on and none was made in the last day (or the clock went back). */
fun updateDue(on: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
    on && (lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= UPDATE_EVERY_MS)

/** One answer of a web server. [location] is the address of a redirect. */
class WebReply(val code: Int, val body: String, val location: String?)

/** Reads text from [input], stopping after about [limit] bytes. */
fun readLimited(input: InputStream, limit: Int): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toString("UTF-8")
}

/** Fetches [address] with a plain GET. A redirect is returned as it is (not followed) when [follow] is false. */
fun webGet(address: String, follow: Boolean, accept: String, timeoutMs: Int = 8000): WebReply {
    val c = URL(address).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = follow
        c.setRequestProperty("User-Agent", "Bento-update-check")
        c.setRequestProperty("Accept", accept)
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = if (stream == null) "" else stream.use { readLimited(it, 1_000_000) }
        return WebReply(code, text, c.getHeaderField("Location"))
    } finally {
        c.disconnect()
    }
}

/**
 * Looks up the newest release and compares it with [installedCode]. Blocking: call it off the main thread.
 * It asks GitHub's API first. When that is refused (a busy shared connection can use up the API's small allowance), it
 * asks the website's "latest release" page, which sends the browser on to the newest release.
 * [apiBase] and [siteBase] are only changed by the tests.
 */
fun checkForUpdate(
    installedCode: Long,
    apiBase: String = "https://api.github.com",
    siteBase: String = "https://github.com"
): UpdateCheck {
    var trouble: String? = null
    try {
        val api = webGet("$apiBase/repos/$UPDATE_REPO/releases/latest", true, "application/vnd.github+json")
        when {
            api.code == 200 -> {
                val release = parseRelease(api.body) ?: return UpdateCheck.NoRelease
                return judgeRelease(release, installedCode)
            }
            api.code == 404 -> return UpdateCheck.NoRelease
        }
        trouble = "HTTP ${api.code}"
    } catch (e: IOException) {
        trouble = e.message ?: e.javaClass.simpleName
    }
    try {
        val page = webGet("$siteBase/$UPDATE_REPO/releases/latest", false, "text/html")
        if (page.code in 300..399) {
            val release = tagFromRedirect(page.location)?.let { releaseFromTag(it) } ?: return UpdateCheck.NoRelease
            return judgeRelease(release, installedCode)
        }
        if (page.code == 404) return UpdateCheck.NoRelease
        trouble = "HTTP ${page.code}"
    } catch (e: IOException) {
        trouble = e.message ?: e.javaClass.simpleName
    }
    return UpdateCheck.Failed(trouble ?: "?")
}

/** What the app remembers about updates: the switch, when it last checked, what it found, and what was put off. */
object UpdatePrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getString("update_check_on", "1") != "0"

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putString("update_check_on", if (on) "1" else "0").apply()
    }

    fun lastCheck(ctx: Context): Long = prefs(ctx).getLong("update_last_check", 0L)

    /** The tag of the version the person chose "Later" for. */
    fun dismissed(ctx: Context): String = prefs(ctx).getString("update_dismissed", "") ?: ""

    fun dismiss(ctx: Context, tag: String) {
        prefs(ctx).edit().putString("update_dismissed", tag).apply()
    }

    /** Keeps the newest version found (so the notice is still there the next time the app opens) and the time of the check. */
    fun record(ctx: Context, result: UpdateCheck, nowMs: Long) {
        if (result is UpdateCheck.Failed) return // not a real answer: ask again the next time
        val e = prefs(ctx).edit().putLong("update_last_check", nowMs)
        if (result is UpdateCheck.Newer) {
            val r = result.release
            e.putString("update_found_tag", r.tag).putString("update_found_page", r.pageUrl).putString("update_found_apk", r.apkUrl ?: "")
        } else {
            e.remove("update_found_tag").remove("update_found_page").remove("update_found_apk")
        }
        e.apply()
    }

    /** The version found by an earlier check, when it is still newer than [installedCode] and wasn't put off. */
    fun waiting(ctx: Context, installedCode: Long): ReleaseInfo? {
        val p = prefs(ctx)
        val tag = p.getString("update_found_tag", "") ?: ""
        val number = releaseNumber(tag) ?: return null
        if (number <= installedCode || tag == dismissed(ctx)) return null
        val page = p.getString("update_found_page", "") ?: ""
        val apk = p.getString("update_found_apk", "") ?: ""
        return ReleaseInfo(tag, number, page.ifEmpty { "https://github.com/$UPDATE_REPO/releases" }, apk.ifEmpty { null })
    }
}

// ---------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------

/** "Version 1.24 is available" with Download and Later. */
@Composable
fun UpdateBanner(release: ReleaseInfo, onDownload: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.primaryContainer)
            .padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            str(R.string.update_available, release.versionName),
            color = cs.onPrimaryContainer,
            style = MaterialTheme.typography.titleSmall
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLater) { Text(str(R.string.update_later)) }
            Button(onClick = onDownload) { Text(str(R.string.update_download)) }
        }
    }
}

/**
 * The update part of the About window: the switch for the automatic check, a button that checks right now, and what
 * the last check said. [onFound] is told about a newer version (so the banner shows it).
 */
@Composable
fun UpdateSection(installed: AppVersion, onFound: (ReleaseInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(UpdatePrefs.enabled(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var newer by remember { mutableStateOf<ReleaseInfo?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(str(R.string.update_title), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(R.string.update_auto), modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = {
                on = it
                UpdatePrefs.setEnabled(ctx, it)
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                line = AppText.get(R.string.status_checking)
                newer = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { checkForUpdate(installed.code) }
                    UpdatePrefs.record(ctx, result, System.currentTimeMillis())
                    line = when (result) {
                        is UpdateCheck.Newer -> AppText.get(R.string.update_available, result.release.versionName)
                        is UpdateCheck.UpToDate -> AppText.get(R.string.update_current, installed.name)
                        is UpdateCheck.NoRelease -> AppText.get(R.string.update_none)
                        is UpdateCheck.Failed -> AppText.get(R.string.err_failed, result.reason)
                    }
                    if (result is UpdateCheck.Newer) {
                        newer = result.release
                        onFound(result.release)
                    }
                    busy = false
                }
            }) { Text(str(R.string.update_check_now)) }
            newer?.let { r ->
                Button(onClick = { openLink(ctx, r.apkUrl ?: r.pageUrl) }) { Text(str(R.string.update_download)) }
            }
        }
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall)
        Text(
            str(R.string.update_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
