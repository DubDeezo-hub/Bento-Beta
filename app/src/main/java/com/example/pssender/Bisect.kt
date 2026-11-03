package com.example.pssender

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// Find the crashing payload: instead of guessing which payload of a chain brings the console down, the app halves the
// suspects each round. The person sends the payloads it names (the ones always needed, plus half of the suspects), uses
// the console as usual, and says whether it crashed; the suspects shrink to the half that was sent, or to the half that
// wasn't. Six suspects take three rounds, eight take four. Where it got to is kept, so a restart (of the console or the
// phone) doesn't lose it.
// ---------------------------------------------------------------------------------------------

/**
 * Where a search stands. [all] is the chain's order, [base] the payloads sent every round, [suspects] the ones still
 * in question, [testing] the ones sent this round. [culprit] is set once one is left; [cleared] when none of the
 * suspects crashed it (the trouble is the always-sent ones, or two payloads together).
 */
data class BisectState(
    val all: List<String>,
    val base: List<String>,
    val suspects: List<String>,
    val testing: List<String>,
    val round: Int = 1,
    val culprit: String? = null,
    val cleared: Boolean = false
) {
    val done: Boolean get() = culprit != null || cleared

    /** What to send this round, in the chain's order. */
    val send: List<String> get() = all.filter { it in base || it in testing }

    /** Rounds still to go at most (each halves the suspects). */
    val roundsLeft: Int get() {
        var n = suspects.size
        var r = 0
        while (n > 1) { n = (n + 1) / 2; r++ }
        return r
    }
}

private fun firstHalf(list: List<String>): List<String> = list.take((list.size + 1) / 2)

/** Starts a search over [all] (a chain's payloads), sending [base] every round. */
fun bisectStart(all: List<String>, base: List<String>): BisectState {
    val suspects = all.filter { it !in base }
    return when (suspects.size) {
        0 -> BisectState(all, base, suspects, emptyList(), cleared = true)
        1 -> BisectState(all, base, suspects, suspects, culprit = null)
        else -> BisectState(all, base, suspects, firstHalf(suspects))
    }
}

/** The answer of a round: whether the console crashed with [s].send. */
fun bisectAnswer(s: BisectState, crashed: Boolean): BisectState {
    if (s.done) return s
    val left = if (crashed) s.testing else s.suspects.filter { it !in s.testing }
    return when {
        left.isEmpty() -> s.copy(suspects = left, testing = emptyList(), cleared = true)
        left.size == 1 && (crashed || s.suspects.size > 1) -> s.copy(suspects = left, testing = emptyList(), culprit = left.single())
        else -> s.copy(suspects = left, testing = firstHalf(left), round = s.round + 1)
    }
}

object BisectStore {
    private const val KEY = "bisect_state"

    fun load(ctx: Context): BisectState? = try {
        val text = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val o = JSONObject(text)
        fun list(name: String): List<String> = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        BisectState(
            list("all"), list("base"), list("suspects"), list("testing"), o.optInt("round", 1),
            o.optString("culprit", "").ifEmpty { null }, o.optBoolean("cleared", false)
        )
    } catch (_: Exception) {
        null
    }

    fun save(ctx: Context, s: BisectState?) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        if (s == null) p.remove(KEY) else p.putString(
            KEY, JSONObject()
                .put("all", JSONArray(s.all)).put("base", JSONArray(s.base)).put("suspects", JSONArray(s.suspects))
                .put("testing", JSONArray(s.testing)).put("round", s.round).put("culprit", s.culprit ?: "").put("cleared", s.cleared)
                .toString()
        )
        p.apply()
    }
}

@Composable
fun BisectDialog(console: Profile, onRun: (Chain) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val chains = remember { ChainStore.load(ctx) }
    var state by remember { mutableStateOf(BisectStore.load(ctx)) }
    var picked by remember { mutableStateOf<Chain?>(null) }
    var base by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reporting by remember { mutableStateOf<String?>(null) }

    fun update(s: BisectState?) {
        state = s
        BisectStore.save(ctx, s)
    }

    reporting?.let { name -> CompatReportDialog(name, "", CompatResult.CRASHED) { reporting = null } }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.bisect_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = state
                when {
                    // 1. Which chain, and which of its payloads go every time
                    s == null -> {
                        Text(str(R.string.bisect_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (chains.isEmpty()) Text(str(R.string.bisect_no_chains))
                        ChipRow {
                            chains.forEach { c ->
                                Chip(c.name, picked?.id == c.id) {
                                    picked = c
                                    // The kernel patch is usually needed for anything else to load
                                    base = c.steps.map { it.payload }.filter { ReloadGuard.kindOf(it) == ReloadKind.KSTUFF }.toSet()
                                }
                            }
                        }
                        picked?.let { c ->
                            Text(str(R.string.bisect_base), style = MaterialTheme.typography.labelLarge)
                            ChipRow {
                                c.steps.map { it.payload }.forEach { n ->
                                    Chip(n, n in base) { base = if (n in base) base - n else base + n }
                                }
                            }
                            Button(enabled = c.steps.size - base.size >= 1, onClick = { update(bisectStart(c.steps.map { it.payload }, base.toList())) }) {
                                Text(str(R.string.bisect_start))
                            }
                        }
                    }
                    // 3. The answer
                    s.culprit != null -> {
                        Text(str(R.string.bisect_found, s.culprit), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Text(str(R.string.bisect_found_next), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { reporting = s.culprit }) { Text(str(R.string.stab_report)) }
                            OutlinedButton(onClick = { DevReport.open(ctx, s.culprit, emptyList()) }) { Text(str(R.string.devreport_button)) }
                        }
                    }
                    s.cleared -> Text(str(R.string.bisect_cleared, joinList(s.base)))
                    // 2. A round
                    else -> {
                        Text(str(R.string.bisect_round, s.round, s.roundsLeft, s.suspects.size), style = MaterialTheme.typography.titleSmall)
                        Text(str(R.string.bisect_send, s.send.joinToString(" → ")))
                        Text(str(R.string.bisect_how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(enabled = console.host.isNotBlank(), onClick = {
                            val steps = chains.flatMap { it.steps }.associateBy { it.payload }
                            onRun(Chain(newChainId(), str0(ctx, s.round), s.send.map { steps[it] ?: ChainStep(it, 10) }))
                        }) { Text(str(R.string.bisect_send_now)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { update(bisectAnswer(s, crashed = true)) }) { Text(str(R.string.bisect_crashed)) }
                            OutlinedButton(onClick = { update(bisectAnswer(s, crashed = false)) }) { Text(str(R.string.bisect_fine)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } },
        dismissButton = { if (state != null) TextButton(onClick = { update(null); picked = null }) { Text(str(R.string.bisect_reset)) } }
    )
}

/** The name of a round's chain ("Search, round 2"). */
private fun str0(ctx: Context, round: Int): String = AppText.get(R.string.bisect_chain_name, round)
