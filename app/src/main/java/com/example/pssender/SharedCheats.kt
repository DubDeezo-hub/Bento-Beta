package com.example.pssender

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// The shared cheat library: cheat lists people made with Crimson Multitool (the helper, related values, by hand) and
// shared for everyone, by game. Shared like the compatibility reports: the app fills in a GitHub issue (marker
// <!-- crimson-cheats -->) that the person sends; the workflow rebuilds the list from its known fields only and keeps it
// in cheats/<title ID>.json on the compat-data branch. People vote whether a list works. The community repositories
// (etaHEN's and GoldHEN's) are read separately, in "Ready-made cheats".
// ---------------------------------------------------------------------------------------------

/** One shared list: who shared it is not kept, only a number. */
data class SharedList(val id: Int, val game: String, val version: String, val cheats: List<Cheat>, val works: Int, val broken: Int)

/** Reads cheats/<title ID>.json: the lists, best voted first. */
fun parseSharedLists(json: String?): List<SharedList> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONObject(json).optJSONArray("lists") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cheats = parseCheats(o.optJSONArray("cheats")?.toString())
            if (cheats.isEmpty()) null
            else SharedList(o.optInt("id"), o.optString("game"), o.optString("version"), cheats, o.optInt("works"), o.optInt("broken"))
        }.sortedWith(compareByDescending<SharedList> { it.works - it.broken }.thenByDescending { it.id })
    } catch (_: Exception) {
        emptyList()
    }
}

/** The cheats to share, with only the fields the library keeps (no signatures, nothing frozen). */
fun sharedCheatsJson(list: List<Cheat>): String {
    val arr = JSONArray()
    for (c in list.take(200)) {
        arr.put(
            JSONObject().put("name", c.name.take(60)).put("address", java.lang.Long.toHexString(c.address)).put("type", c.type.name)
                .put("value", c.value.take(40)).apply {
                    if (c.group.isNotBlank()) put("group", c.group.take(40))
                    if (c.ptr.isNotBlank()) put("ptr", c.ptr)
                    if (c.note.isNotBlank()) put("note", c.note.take(200))
                    if (c.rule != FreezeRule.EXACT) put("rule", c.rule.wire)
                }
        )
    }
    return arr.toString()
}

/** The text of a share report. */
fun shareCheatsBody(titleId: String, game: String, version: String, cheats: List<Cheat>): String =
    "<!-- crimson-cheats -->\ntitleId: $titleId\ngame: ${game.take(80)}\nversion: ${version.take(16)}\n\n```json\n" +
        sharedCheatsJson(cheats) + "\n```\n\nShared from Crimson Multitool for everyone to use.\n"

/** A shared list is sent as a link, which can only be so long; a bigger list goes as a file instead. */
const val SHARE_BODY_MAX = 6500

object SharedCheats {
    private fun url(titleId: String) = "https://raw.githubusercontent.com/$UPDATE_REPO/compat-data/cheats/$titleId.json"

    /** The shared lists for [titleId] (none when there are none, or GitHub can't be reached). Blocking. */
    fun fetch(titleId: String): List<SharedList> = try {
        val r = webGet(url(titleId.uppercase()), true, "application/json")
        if (r.code == 200) parseSharedLists(r.body) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun open(ctx: Context, title: String, body: String): Boolean = try {
        val u = "https://github.com/$UPDATE_REPO/issues/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(body)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    /** Opens the filled-in share report; false when the list is too big for a link (share it as a file instead). */
    fun share(ctx: Context, titleId: String, game: String, version: String, cheats: List<Cheat>): Boolean {
        val body = shareCheatsBody(titleId, game, version, cheats)
        if (body.length > SHARE_BODY_MAX) return false
        return open(ctx, "Cheats for $titleId ${game.take(40)}".trim(), body)
    }

    fun vote(ctx: Context, titleId: String, list: Int, works: Boolean) {
        val v = if (works) "works" else "broken"
        open(ctx, "Cheat list vote: $titleId #$list ($v)", "<!-- crimson-cheat-vote -->\ntitleId: $titleId\nlist: $list\nvote: $v\n\nFrom Crimson Multitool.\n")
    }
}

@Composable
internal fun SharedCheatsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val m = MemorySession
    val game = m.snapshot?.game
    val titleId = game?.titleId.orEmpty()
    var lists by remember { mutableStateOf<List<SharedList>?>(null) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(titleId) { if (titleId.isNotBlank()) lists = withContext(Dispatchers.IO) { SharedCheats.fetch(titleId) } }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.shared_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.shared_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (titleId.isBlank()) {
                    Text(str(R.string.memory_no_game))
                    return@Column
                }
                // Share this game's own cheats with everyone
                TextButton(enabled = m.cheats.isNotEmpty(), onClick = {
                    status = if (SharedCheats.share(ctx, titleId, game?.name.orEmpty(), game?.version.orEmpty(), m.cheats)) ""
                    else AppText.get(R.string.shared_too_big)
                }) { Text(str(R.string.shared_share, m.cheats.size)) }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                val l = lists
                when {
                    l == null -> Text("…")
                    l.isEmpty() -> Text(str(R.string.shared_none, titleId))
                    else -> l.forEach { s ->
                        Column {
                            Text(
                                str(R.string.shared_row, s.game.ifBlank { titleId }, s.version.ifBlank { "?" }, s.cheats.size),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                str(R.string.shared_votes, s.works, s.broken) +
                                    if (CheatFiles.sameVersion(s.version, game?.version.orEmpty())) " · " + str(R.string.shared_your_version) else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row {
                                TextButton(onClick = {
                                    m.importText(JSONObject().put("cheats", JSONArray(sharedCheatsJson(s.cheats))).toString())
                                    status = m.status
                                }) { Text(str(R.string.memory_import)) }
                                TextButton(onClick = { SharedCheats.vote(ctx, titleId, s.id, true) }) { Text(str(R.string.shared_works)) }
                                TextButton(onClick = { SharedCheats.vote(ctx, titleId, s.id, false) }) { Text(str(R.string.shared_broken)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}
