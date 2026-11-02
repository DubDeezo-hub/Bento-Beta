package com.example.pssender

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------------------------
// Home: the first screen. A ring shows how much of the console answers (each service that answers fills its part),
// with the main things to do right under it: send a payload, the payloads you use most as round buttons, links to
// install, and the games on the console. Most taps start here.
// ---------------------------------------------------------------------------------------------

/** The ring around the console's state: [up] of [total] services answer. */
@Composable
fun StatusRing(up: Int, total: Int, headline: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    // The ring fills (and empties) smoothly as services come up or go away
    val fraction by animateFloatAsState(if (total <= 0) 0f else up.toFloat() / total, animationSpec = tween(700), label = "ring")
    val track = cs.surfaceContainerHigh
    Box(
        modifier = modifier
            .size(212.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = str(R.string.console_change_ip), onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(212.dp)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(
                    Brush.linearGradient(listOf(Color(0xFFFF2A3D), BLOOD, BLOOD_DARK), Offset.Zero, Offset(size.width, size.height)),
                    -90f, 360f * fraction, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF2A060C), Color(0xFF070304)), Offset(size.width / 2, size.height * 0.35f), size.width * 0.45f),
                radius = size.width / 2 - stroke * 2.4f
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
            Text(
                headline, color = if (up > 0 && up == total) BLOOD_BRIGHT else cs.onSurface, fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis
            )
            if (sub.isNotEmpty()) Text(sub, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A payload as a round button with its first letter, its name under it. Lit red when it is a big one (etaHEN, kstuff). */
@Composable
private fun PayloadBubble(name: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ring = if (ReloadGuard.kindOf(name) != null) BLOOD_BRIGHT else cs.outlineVariant
    val shown = name.substringBeforeLast('.')
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF2A0A10), cs.surface)))
                .border(2.dp, ring, CircleShape)
                .clickable(onClickLabel = str(R.string.action_send), onClick = onClick)
                .semantics { contentDescription = shown },
            contentAlignment = Alignment.Center
        ) {
            Text(shown.take(1).lowercase(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(shown, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A game on the console as a circle (its icon, or its first letters). */
@Composable
private fun GameCircle(app: AppEntry, updateReady: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .border(2.dp, if (updateReady) BLOOD_BRIGHT else cs.outlineVariant, CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF7A0A18), Color(0xFF12030A)))),
            contentAlignment = Alignment.Center
        ) {
            val icon = app.icon
            if (icon != null) Image(icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(82.dp).clip(CircleShape))
            else Text(app.shownName.take(2), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(app.shownName, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
}

/**
 * The Home screen. [install] is the "install from links" part (the old Install tab), shown under the payloads.
 * [onOpenPayloads] opens the Payloads screen; [ftp] lets it read the console's games.
 */
@Composable
fun HomeTab(
    host: String,
    level: ConsoleLevel,
    checks: List<ServiceCheck>,
    type: ConsoleType,
    loaderPort: String,
    ftp: FtpSettings?,
    onEdit: () -> Unit,
    onFind: () -> Unit,
    onCheck: () -> Unit,
    onOpenPayloads: () -> Unit,
    shelfVersion: Int = 0,
    upLine: String = "",
    install: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val counted = checks.filter { it.port != null }
    val up = counted.count { it.up == true }
    val down = counted.filter { it.up == false }.map { it.label }
    var status by remember { mutableStateOf("") }
    var reloadAsk by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    // Read again when Home opens, and when a payload is saved while it is open
    val shelf = remember(shelfVersion) { shelfList(shelfDir(ctx)) }

    // The console's games, read once when they aren't known yet
    LaunchedEffect(ftp) { if (ftp != null && ConsoleApps.apps.isEmpty()) ConsoleApps.load(ctx, ftp) }

    reloadAsk?.let { (why, go) ->
        AlertDialog(
            onDismissRequest = { reloadAsk = null },
            title = { Text(str(R.string.reload_warn_title)) },
            text = { Text(why) },
            confirmButton = { TextButton(onClick = { reloadAsk = null }) { Text(str(R.string.action_cancel)) } },
            dismissButton = { TextButton(onClick = { reloadAsk = null; go() }) { Text(str(R.string.reload_send_anyway)) } }
        )
    }

    val send: (String) -> Unit = { name ->
        val port = loaderPort.trim().toIntOrNull()
        if (host.isBlank() || port == null) onEdit() else scope.launch {
            val go: () -> Unit = {
                status = AppText.get(R.string.payload_sending, name)
                scope.launch {
                    val r = sendPayloadReply(File(shelfDir(ctx), name), host.trim(), port)
                    if (r.ok) notePayloadSent(host, name)
                    buzz(view, r.ok) // a short buzz: sent, or not
                    status = AppText.get(R.string.payload_result, name, r.text)
                }
            }
            val why = withContext(Dispatchers.IO) { ReloadGuard.warning(host, listOf(name), type) }
            if (why == null) go() else reloadAsk = why to go
        }
    }

    // Upright: one column. On its side (wide and low): the ring on the left, the rest on the right
    val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val ringPart: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            StatusRing(up, counted.size, consoleHeadline(level, down, true, type), host.trim(), onEdit)
            // How long ago the last jailbreak was (the loader started answering)
            if (upLine.isNotEmpty()) Text(upLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).horizontalScroll(rememberScrollState())
            ) {
                checks.forEach { c -> ServicePill(c, level) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(CircleShape)
                        .background(bloodGradient)
                        .clickable(onClick = onOpenPayloads),
                    contentAlignment = Alignment.Center
                ) { Text(str(R.string.payload_send), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                if (level == ConsoleLevel.UNSET || level == ConsoleLevel.OFFLINE || level == ConsoleLevel.PARTIAL) {
                    val findIt = level == ConsoleLevel.UNSET || level == ConsoleLevel.OFFLINE
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .clip(CircleShape)
                            .background(cs.surfaceContainer)
                            .clickable(onClick = if (findIt && host.isBlank()) onFind else onCheck),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (findIt && host.isBlank()) str(R.string.find_button, type.model) else str(R.string.diag_button),
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }

    }
    val restPart: @Composable () -> Unit = {
        SectionTitle(str(R.string.home_quick_send))
        if (shelf.isEmpty()) {
            Text(str(R.string.payload_shelf_empty, type.hen), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                shelf.forEach { item -> PayloadBubble(item.name) { send(item.name) } }
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        // Newer versions of payloads on the shelf (checked when the app opens): a tap leads to Payloads, where they are
        if (StoreUpdates.newer.isNotEmpty()) {
            Text(
                str(R.string.store_updates_out, StoreUpdates.newer.joinToString(", ")),
                color = BLOOD_BRIGHT,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenPayloads).padding(vertical = 6.dp)
            )
        }

        val apps = ConsoleApps.apps
        if (apps.isNotEmpty()) {
            SectionTitle(str(R.string.home_on_console))
            // Game updates, from the community patch lists (never Sony): the games with a newer patch get a red ring
            val u = GameUpdates
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        u.checking -> str(R.string.apps_updates_checking, u.done, u.total)
                        u.results.isEmpty() -> str(R.string.apps_updates_hint)
                        u.available > 0 -> str(R.string.apps_updates_found, u.available)
                        else -> str(R.string.apps_updates_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (u.available > 0) BLOOD_BRIGHT else cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (ftp != null && !u.checking) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(cs.surfaceContainer)
                            .clickable {
                                scope.launch {
                                    val key = "${ftp.host}:${ftp.port}"
                                    val patches = withContext(Dispatchers.IO) { try { ConsoleApps.patchVersions(ftp) } catch (_: Exception) { emptyMap() } }
                                    GameUpdates.check(key, apps, patches, ConsoleFirmware.get(ctx, ActiveConsole.id), type == ConsoleType.PS5)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) { Text(str(R.string.apps_updates_check), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                apps.take(16).forEach { a -> GameCircle(a, GameUpdates.results[a.id] is UpdateState.Available) }
            }
        }

        // The old Install tab: it brings its own title
        Box(Modifier.padding(top = 12.dp)) { install() }
    }
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(0.45f)) { ringPart() }
            Column(Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(10.dp)) { restPart() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ringPart()
            restPart()
        }
    }
}
