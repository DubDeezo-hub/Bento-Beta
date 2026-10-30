package com.example.pssender

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// Payload chains on the screen: the list on the Payload tab, the window that makes or changes one, and the card that
// shows a running chain above the bottom bar.
// ---------------------------------------------------------------------------------------------

/** The new order of [steps] when the one at [index] moves up ([by] = -1) or down ([by] = 1). Unchanged when it can't move. */
fun movedStep(steps: List<ChainStep>, index: Int, by: Int): List<ChainStep> {
    val to = index + by
    if (index !in steps.indices || to !in steps.indices) return steps
    val list = steps.toMutableList()
    val step = list.removeAt(index)
    list.add(to, step)
    return list
}

/** [steps] with the pause of the one at [index] set to [seconds]. */
fun withWait(steps: List<ChainStep>, index: Int, seconds: Int): List<ChainStep> =
    steps.mapIndexed { i, s -> if (i == index) s.copy(waitSeconds = seconds.coerceIn(0, CHAIN_MAX_WAIT)) else s }

/**
 * The chains on the Payload tab: each can be sent with one tap or edited; "New chain" makes another. [canSend] is false
 * while no console address is set or a chain is running. [shelf] are the payloads a chain can be made of.
 */
@Composable
fun ChainsSection(
    chains: List<Chain>,
    shelf: List<ShelfItem>,
    canSend: Boolean,
    onRun: (Chain) -> Unit,
    onSave: (Chain) -> Unit,
    onDelete: (Chain) -> Unit,
    onPin: (Chain) -> Unit
) {
    var editing by remember { mutableStateOf<Chain?>(null) }
    val open = editing

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TipTitle(str(R.string.chains_title), Tip.PAYLOAD_CHAINS, style = MaterialTheme.typography.titleMedium)
        Text(str(R.string.chains_hint), style = MaterialTheme.typography.bodySmall)
        chains.forEach { chain ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(chain.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chainSummary(chain), style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = canSend, onClick = { onRun(chain) }) { Text(str(R.string.action_send)) }
                TextButton(onClick = { editing = chain }) { Text(str(R.string.action_edit)) }
            }
        }
        Button(
            enabled = chains.size < CHAINS_MAX,
            onClick = { editing = Chain(newChainId(), "", emptyList()) }
        ) { Text(str(R.string.chain_new)) }
        // A chain that runs by itself when this console becomes ready (after a jailbreak)
        if (chains.isNotEmpty()) {
            val ctx = LocalContext.current
            val profileId = ActiveConsole.id
            var auto by remember(profileId) { mutableStateOf(AutoChain.get(ctx, profileId)) }
            TipTitle(str(R.string.chain_auto_title, ActiveConsole.type.model), Tip.CHAIN_AUTO, style = MaterialTheme.typography.labelLarge)
            ChipRow {
                Chip(str(R.string.chain_auto_off), auto.isEmpty() || chains.none { it.id == auto }) {
                    auto = ""
                    AutoChain.set(ctx, profileId, "")
                }
                chains.forEach { c ->
                    Chip(c.name, auto == c.id) {
                        auto = c.id
                        AutoChain.set(ctx, profileId, c.id)
                    }
                }
            }
        }
    }

    if (open != null) {
        ChainEditDialog(
            initial = open,
            isNew = chains.none { it.id == open.id },
            shelf = shelf,
            onSave = {
                onSave(it)
                editing = null
            },
            onDelete = {
                onDelete(open)
                editing = null
            },
            onPin = { onPin(it) },
            onClose = { editing = null }
        )
    }
}

/**
 * The window that makes or changes a chain: its name and its payloads in order, each with the pause after it (the last
 * one has none). A chain can be put on the home screen from here, and deleted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChainEditDialog(
    initial: Chain,
    isNew: Boolean,
    shelf: List<ShelfItem>,
    onSave: (Chain) -> Unit,
    onDelete: () -> Unit,
    onPin: (Chain) -> Unit,
    onClose: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var steps by remember { mutableStateOf(initial.steps) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val onShelf = shelf.map { it.name }.toSet()
    val draft = Chain(initial.id, name.trim(), steps)

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(str(R.string.chain_pick_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (shelf.isEmpty()) Text(str(R.string.payload_shelf_empty, ActiveConsole.type.hen), style = MaterialTheme.typography.bodySmall)
                    shelf.forEach { item ->
                        Text(
                            item.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    steps = steps + ChainStep(item.name, CHAIN_DEFAULT_WAIT)
                                    picking = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(str(R.string.action_cancel)) } }
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(str(R.string.console_delete_title, initial.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text(str(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(str(R.string.action_cancel)) } }
        )
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(if (isNew) R.string.chain_new else R.string.chain_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Field(str(R.string.console_name_label), name) { name = it.take(CHAIN_NAME_MAX) }
                Text(str(R.string.chain_steps_title, CHAIN_MAX_STEPS), style = MaterialTheme.typography.labelLarge)
                steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${i + 1}. ${step.payload}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (step.payload !in onShelf) {
                                Text(
                                    str(R.string.chain_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(enabled = i > 0, onClick = { steps = movedStep(steps, i, -1) }) {
                            Icon(ArrowUpIcon, contentDescription = str(R.string.chain_move_up))
                        }
                        IconButton(enabled = i < steps.size - 1, onClick = { steps = movedStep(steps, i, 1) }) {
                            Icon(ArrowDownIcon, contentDescription = str(R.string.chain_move_down))
                        }
                        IconButton(onClick = { steps = steps.filterIndexed { k, _ -> k != i } }) {
                            Icon(CloseIcon, contentDescription = str(R.string.action_remove))
                        }
                    }
                    if (i < steps.size - 1) {
                        NumberField(str(R.string.chain_wait_label), step.waitSeconds.toString()) { v ->
                            steps = withWait(steps, i, parseWait(v))
                        }
                    }
                }
                TextButton(enabled = steps.size < CHAIN_MAX_STEPS, onClick = { picking = true }) {
                    Text(str(R.string.chain_add_step))
                }
                if (!isNew) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onPin(initial) }) { Text(str(R.string.chain_pin)) }
                        TextButton(onClick = { deleting = true }) { Text(str(R.string.action_delete)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = draft.name.isNotEmpty() && steps.isNotEmpty(), onClick = { onSave(draft) }) {
                Text(str(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_cancel)) } }
    )
}

/**
 * The running chain, pinned above the bottom bar like a transfer: its name, what it is doing now and a Stop button.
 * When it is over, the card says how it went and stays until dismissed (a chain that worked hides itself after a while).
 */
@Composable
fun ChainCard(s: ChainState, onStop: () -> Unit, onDismiss: () -> Unit) {
    if (s.name.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val sc = statusColors()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(cs.surfaceContainerHigh)
            .border(1.dp, cs.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (s.active) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    s.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onStop) { Text(str(R.string.action_stop)) }
            }
            val done = (s.step - 1).coerceAtLeast(0) + if (s.waitLeft >= 0) 1 else 0
            LinearProgressIndicator(
                progress = { if (s.steps > 0) (done.toFloat() / s.steps.toFloat()).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = cs.primary,
                trackColor = cs.surfaceVariant
            )
            Text(
                chainProgressText(s),
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (s.console.isNotBlank()) {
                Text(
                    str(R.string.transfer_sending_to, s.console),
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (s.ok) CheckIcon else AlertIcon,
                    contentDescription = str(if (s.ok) R.string.desc_done else R.string.desc_problem),
                    tint = if (s.ok) sc.good else sc.bad,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        s.message,
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                    HelpLink(s.message)
                }
                TextButton(onClick = onDismiss) { Text(str(R.string.action_dismiss)) }
            }
        }
    }
}
