package com.example.pssender // change to match your project's package name

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// The frame around every screen: header, console card, status line, transfer card and bottom bar
// ---------------------------------------------------------------------------------------------

/** A small filled circle, used as a status light. */
@Composable
fun StatusDot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** A small hollow circle: the same light turned off, so "off" doesn't rely on color alone. */
@Composable
fun StatusRing(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).border(2.dp, color, CircleShape))
}

/** The selected console, shown as a pill in the accent color of that console. Tap to switch consoles. */
@Composable
fun ConsoleChip(name: String, accent: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .widthIn(max = 190.dp)
            .clip(CircleShape)
            .border(1.dp, cs.outlineVariant, CircleShape)
            .clickable(onClickLabel = str(R.string.console_switch), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusDot(accent)
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text("▾", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
}

/** The title with the credit under it, and the selected console on the other side. */
@Composable
fun AppHeader(consoleName: String, accent: Color, onAbout: () -> Unit, onConsoles: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).clickable(onClickLabel = str(R.string.about_button), onClick = onAbout)) {
            Text(str(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(
                str(R.string.credit_line, str(R.string.credit_name)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ConsoleChip(consoleName, accent, onConsoles)
    }
}

/** One service on the console card: a light and its name. A filled light answers, a hollow one doesn't. */
@Composable
private fun ServicePill(check: ServiceCheck, level: ConsoleLevel, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val sc = statusColors()
    val state: Boolean? = if (check.port == null) null else check.up
    Row(
        modifier = modifier.clip(CircleShape).background(cs.surface).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        when (state) {
            true -> StatusDot(sc.good, 8.dp)
            false -> StatusRing(if (level == ConsoleLevel.OFFLINE) sc.bad else sc.warn, 8.dp)
            null -> StatusRing(cs.onSurfaceVariant, 8.dp)
        }
        Text(check.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Shows whether the selected console is reachable: a colored light and title, the address, and (unless
 * [compact]) which services answer. Tap it to change the address.
 */
@Composable
fun ConsoleCard(
    host: String,
    level: ConsoleLevel,
    checks: List<ServiceCheck>,
    compact: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onFind: (() -> Unit)? = null,
    type: ConsoleType = ConsoleType.PS5
) {
    val cs = MaterialTheme.colorScheme
    val sc = statusColors()
    val tone = when (level) {
        ConsoleLevel.READY -> sc.good
        ConsoleLevel.PARTIAL -> sc.warn
        ConsoleLevel.OFFLINE -> sc.bad
        else -> cs.onSurfaceVariant
    }
    val down = checks.filter { it.port != null && it.up == false }.map { it.label }
    val hint = consoleHint(level, down, type)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, shape)
            .clickable(onClickLabel = str(R.string.console_change_ip), onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(tone)
            Text(
                consoleHeadline(level, down, compact, type),
                color = tone,
                style = MaterialTheme.typography.titleMedium,
                maxLines = if (compact) 2 else 1, // the short card may need a second line to name what is off
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (host.isNotBlank()) {
                Text(
                    host,
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp) // a long name must never squeeze the title away
                )
            }
            // The arrow points the way the text continues: right in left-to-right languages, left in Arabic and Hebrew
            Text(
                if (LocalLayoutDirection.current == LayoutDirection.Rtl) "‹" else "›",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (!compact) {
            if (level != ConsoleLevel.UNSET && checks.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    checks.forEach { c -> ServicePill(c, level, Modifier.weight(1f)) }
                }
            }
            if (hint != null) {
                Text(hint, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            // With no address, or an address nothing answers at, the quickest way on is to look for the console
            if (onFind != null && (level == ConsoleLevel.UNSET || level == ConsoleLevel.OFFLINE)) {
                TextButton(onClick = onFind) { Text(str(R.string.find_button, type.model)) }
            }
        }
    }
}

/**
 * Asks for the console's IP address. The change is saved as you type. [onFind] opens the window that looks for the console.
 * [type] says where the console shows its address (the menus of a PS5 and a PS4 differ).
 */
@Composable
fun ConsoleEditDialog(
    host: String,
    onHostChange: (String) -> Unit,
    onFind: () -> Unit,
    onClose: () -> Unit,
    type: ConsoleType = ConsoleType.PS5
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { TipTitle(str(R.string.console_ip_title), Tip.CONSOLE, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(str(R.string.console_ip_label), host, onHostChange)
                Text(
                    str(if (type == ConsoleType.PS4) R.string.console_ip_help_ps4 else R.string.console_ip_help),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onFind) { Text(str(R.string.find_button, type.model)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_done)) } }
    )
}

/** One place the bottom bar can go to. [label] is the text of its name (an R.string value). */
class NavItem(val label: Int, val icon: ImageVector)

/** The bottom bar: one button per screen, the current one in the accent color. */
@Composable
fun BottomBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().background(cs.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val tint = if (on) cs.primary else cs.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 4.dp)
                    ) {
                        Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Text(
                        str(item.label),
                        color = tint,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

/** The tabs down the left side, for wide screens: the same buttons as [BottomBar], one above the other. */
@Composable
fun SideRail(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(92.dp)
                .background(cs.surfaceContainer)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val tint = if (on) cs.primary else cs.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 4.dp)
                    ) {
                        Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Text(
                        str(item.label),
                        color = tint,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(cs.outlineVariant))
    }
}

/** "Sending to My PS5 · 1.2 GB of 5 GB" for a running transfer (in the app's language). */
fun transferDetail(t: TransferInfo): String {
    val console = t.console
    val where = when {
        console.isBlank() -> AppText.get(if (t.download) R.string.transfer_receiving else R.string.transfer_sending)
        t.download -> AppText.get(R.string.transfer_receiving_from, console)
        else -> AppText.get(R.string.transfer_sending_to, console)
    }
    return when {
        t.total > 0 -> AppText.get(R.string.transfer_detail_total, where, fmtBytes(t.done), fmtBytes(t.total))
        t.done > 0 -> AppText.get(R.string.transfer_detail_done, where, fmtBytes(t.done))
        else -> where
    }
}

/**
 * Progress of the current transfer, pinned above the bottom bar so it shows on every screen. While it runs there is a
 * Pause (or Resume) button and a Cancel button, the speed and time left, the speed limit ([limit] in bytes per second,
 * 0 for none; tap it to change it), and how many transfers wait behind it ([waiting], tap it to see them). A
 * transfer that failed stays here with Try again and Skip until you decide, and the queue waits for that. A transfer
 * that ended stays until dismissed (a successful one hides itself after a while).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferCard(
    t: TransferInfo,
    waiting: Int,
    limit: Long,
    onCancel: () -> Unit,
    onPauseResume: () -> Unit,
    onLimit: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onQueue: () -> Unit
) {
    if (t.label.isEmpty()) return
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
        if (t.active) {
            val known = t.total > 0
            val fraction = if (known) (t.done.toFloat() / t.total.toFloat()).coerceIn(0f, 1f) else 0f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    t.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (known) AppText.get(R.string.percent_value, (fraction * 100).toInt()) else fmtBytes(t.done),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (known) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.primary,
                    trackColor = cs.surfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.primary,
                    trackColor = cs.surfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    transferDetail(t),
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onPauseResume) {
                    Icon(
                        if (t.paused) PlayIcon else PauseIcon,
                        contentDescription = str(if (t.paused) R.string.transfer_resume else R.string.transfer_pause),
                        tint = cs.primary
                    )
                }
                TextButton(onClick = onCancel) { Text(str(R.string.action_cancel)) }
            }
            // A held transfer says so; a broken connection says so (and when the next try comes); otherwise the speed
            // and the time left
            val note = when {
                t.paused -> str(R.string.transfer_paused)
                t.status.isNotEmpty() -> t.status
                else -> speedAndTime(t)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note,
                    color = if (t.paused || t.status.isNotEmpty()) sc.warn else cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onLimit) {
                    Text(if (limit > 0L) str(R.string.speed_limit_value, fmtRate(limit)) else str(R.string.speed_limit_title))
                }
                if (waiting > 0) {
                    TextButton(onClick = onQueue) { Text(str(R.string.transfer_queued, waiting)) }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (t.ok) CheckIcon else AlertIcon,
                    contentDescription = str(if (t.ok) R.string.desc_done else R.string.desc_problem),
                    tint = if (t.ok) sc.good else sc.bad,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(t.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        t.message,
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    HelpLink(t.message)
                }
                if (!t.parked) {
                    TextButton(onClick = onDismiss) { Text(str(R.string.action_dismiss)) }
                }
            }
            if (t.parked) {
                // The queue waits here: a failure that may pass can be tried again; any failure can be skipped
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (t.canRetry) {
                        Button(onClick = onRetry) { Text(str(R.string.action_try_again)) }
                        TextButton(onClick = onSkip) { Text(str(R.string.action_skip)) }
                    } else {
                        TextButton(onClick = onSkip) { Text(str(R.string.action_dismiss)) }
                    }
                    if (waiting > 0) {
                        TextButton(onClick = onQueue) { Text(str(R.string.transfer_queued, waiting)) }
                    }
                }
            }
        }
    }
}

/** The transfers that wait for their turn. Each can be taken out, or all of them cleared (the running one is not affected). */
@Composable
fun QueueDialog(
    items: List<QueueEntry<TransferRequest>>,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.queue_title)) },
        text = {
            if (items.isEmpty()) {
                Text(str(R.string.queue_empty))
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.item.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onRemove(entry.id) }) { Text(str(R.string.action_remove)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_done)) } },
        dismissButton = {
            if (items.isNotEmpty()) TextButton(onClick = onClear) { Text(str(R.string.queue_clear)) }
        }
    )
}

/**
 * The speed limit window: the limits to pick from. A pick is saved and applies to the running transfer at once, so it can
 * be changed while a transfer runs.
 */
@Composable
fun SpeedLimitDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val current by TransferState.limit.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.speed_limit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(str(R.string.speed_limit_hint), style = MaterialTheme.typography.bodySmall)
                SpeedLimits.choices.forEach { value ->
                    val on = value == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = on, role = Role.RadioButton, onClick = {
                                SpeedLimits.save(ctx, value)
                                TransferState.setSpeedLimit(value)
                            }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = on, onClick = null)
                        Text(
                            if (value == 0L) str(R.string.speed_limit_off) else fmtRate(value),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.action_done)) } }
    )
}

/** The one-line answer to the last action ("Sending link to PS5…", "Installer accepted it", ...). Tap to hide. */
@Composable
fun StatusStrip(text: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant)
            .clickable(onClickLabel = str(R.string.status_hide), onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text,
            color = cs.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        HelpLink(text)
        Text("✕", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
