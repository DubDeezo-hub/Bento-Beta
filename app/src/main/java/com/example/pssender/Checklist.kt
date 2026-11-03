package com.example.pssender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// The first-jailbreak checklist: the steps to a steady jailbreak, each ticked off when it is done, with a button where
// the app can do it. The load order it suggests follows the load-order rules (ChainOrder): on a PS5 kstuff, then
// ShadowMountPlus, then etaHEN, and no separate elfldr next to etaHEN; on a PS4 GoldHEN.
// ---------------------------------------------------------------------------------------------

/** The kinds of payload the suggested order is made of, in that order. */
private fun orderKinds(type: ConsoleType): List<(String) -> Boolean> =
    if (type == ConsoleType.PS4) listOf({ n: String -> "goldhen" in n.lowercase() })
    else listOf(
        { n: String -> "kstuff" in n.lowercase() },
        { n: String -> "shadowmount" in n.lowercase() },
        { n: String -> "etahen" in n.lowercase() }
    )

/**
 * The suggested load order from the payloads on the [shelf] (the newest-looking name of each kind; a kind that isn't
 * there is left out), and how many kinds are missing.
 */
fun suggestedOrder(shelf: List<String>, type: ConsoleType): Pair<List<String>, Int> {
    val kinds = orderKinds(type)
    val picked = kinds.mapNotNull { k -> shelf.filter(k).maxWithOrNull(String.CASE_INSENSITIVE_ORDER) }
    return picked to (kinds.size - picked.size)
}

/** A chain of [names] with a pause of [pause] seconds after each, named [name]. */
fun orderChain(names: List<String>, name: String, pause: Int = 10): Chain =
    Chain(newChainId(), name, names.map { ChainStep(it, pause) })

@Composable
fun ChecklistDialog(console: Profile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // goes up when a step changes something, to look again
    val shelf = remember(tick) { shelfList(shelfDir(ctx)).map { it.name } }
    val chains = remember(tick) { ChainStore.load(ctx) }
    val firmware = remember(tick) { ConsoleFirmware.get(ctx, console.id) }
    val (order, missing) = suggestedOrder(shelf, console.type)
    val chainName = AppText.get(R.string.check_chain_name)
    val haveChain = order.isNotEmpty() && chains.any { c -> c.steps.map { it.payload } == order }
    val scope = rememberCoroutineScope()
    var recipe by remember { mutableStateOf<Pair<List<String>, CompatCounts>?>(null) }
    LaunchedEffect(tick, firmware) {
        if (firmware.isNotEmpty()) {
            recipe = withContext(Dispatchers.IO) {
                try { bestRecipe(CompatData.recipes(ctx), compatWhere(console.type.model, firmware), shelf) } catch (_: Exception) { null }
            }
        }
    }
    var updates by remember { mutableStateOf<UpdateBlockResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    val u = updates
    val updatesText = when {
        u == null -> str(R.string.upd_how)
        !u.found -> str(R.string.upd_not_found)
        u.blocked -> str(R.string.upd_ok, u.rules, u.file)
        else -> str(R.string.upd_none, u.file)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.check_title, console.type.model)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step(console.host.isNotBlank(), str(R.string.check_address, console.type.model), str(R.string.check_address_how))
                Step(firmware.isNotEmpty(), str(R.string.check_firmware), str(R.string.check_firmware_how))
                Step(
                    missing == 0, str(R.string.check_payloads),
                    if (missing == 0) str(R.string.check_payloads_ok, joinList(order)) else str(R.string.check_payloads_missing, missing)
                )
                Step(haveChain, str(R.string.check_order), if (order.isEmpty()) "" else order.joinToString(" → ")) {
                    if (order.isNotEmpty() && !haveChain) {
                        TextButton(onClick = {
                            ChainStore.save(ctx, ChainStore.load(ctx) + orderChain(order, chainName))
                            ChainStore.changed.update { it + 1 }
                            tick++
                        }) { Text(str(R.string.check_make_chain)) }
                    }
                }
                Step(SmartChain.on, str(R.string.chain_smart_title), str(R.string.chain_smart_hint)) {
                    if (!SmartChain.on) TextButton(onClick = { SmartChain.set(ctx, true); tick++ }) { Text(str(R.string.check_turn_on)) }
                }
                Step(BlackBox.on, str(R.string.blackbox_title), str(R.string.blackbox_detail)) {
                    if (!BlackBox.on) TextButton(onClick = { BlackBox.set(ctx, true); tick++ }) { Text(str(R.string.check_turn_on)) }
                }
                // System updates end a jailbreak: are they blocked on the console (nanoDNS)?
                Step(updates?.blocked, str(R.string.upd_title), updatesText) {
                    TextButton(enabled = ActiveConsole.ftp != null && !checking, onClick = {
                        val s = ActiveConsole.ftp ?: return@TextButton
                        checking = true
                        scope.launch {
                            updates = try { withContext(Dispatchers.IO) { UpdateBlock.check(s) } } catch (_: Exception) { UpdateBlockResult("", 0) }
                            checking = false
                        }
                    }) { Text(str(if (checking) R.string.snap_working else R.string.upd_check)) }
                }
                // What worked for others on this firmware, as a whole load order (the shared recipes)
                val best = recipe
                if (best != null) {
                    Step(null, str(R.string.recipe_title, console.type.model, firmware), str(R.string.recipe_row, best.first.joinToString(" → "), best.second.worked, best.second.crashed)) {
                        TextButton(onClick = {
                            ChainStore.save(ctx, ChainStore.load(ctx) + orderChain(best.first, AppText.get(R.string.recipe_chain_name, console.type.model, firmware)))
                            ChainStore.changed.update { it + 1 }
                            tick++
                        }) { Text(str(R.string.check_make_chain)) }
                    }
                }
                // Tell others how a chain went (only with the firmware known, so the report says where)
                if (firmware.isNotEmpty() && chains.isNotEmpty()) {
                    Step(null, str(R.string.recipe_report_title), str(R.string.recipe_report_how)) {
                        chains.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { openUrl(ctx, recipeReportUrl(c.steps.map { it.payload }, console.type.model, firmware, true)) }) { Text(str(R.string.compat_worked)) }
                                TextButton(onClick = { openUrl(ctx, recipeReportUrl(c.steps.map { it.payload }, console.type.model, firmware, false)) }) { Text(str(R.string.compat_crashed)) }
                            }
                        }
                    }
                }
                Step(null, str(R.string.check_others), str(R.string.check_others_how))
                Step(null, str(R.string.check_crash), str(R.string.check_crash_how))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}

/** One step: done (✓), still to do (○), or advice (•), its title, a line about it, and an optional button. */
@Composable
private fun Step(done: Boolean?, title: String, detail: String, action: @Composable () -> Unit = {}) {
    val mark = when (done) {
        true -> "✓"
        false -> "○"
        null -> "•"
    }
    val said = str(when (done) { true -> R.string.check_done; false -> R.string.check_todo; null -> R.string.check_tip })
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics { contentDescription = "$said: $title" }) {
        Text(
            mark,
            color = if (done == true) statusColors().good else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

/** Opens [url] in the browser (a report on GitHub). */
fun openUrl(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
