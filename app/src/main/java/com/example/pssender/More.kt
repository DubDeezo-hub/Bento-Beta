package com.example.pssender

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// More: everything that isn't one of the four main screens, as a short list of round rows: the web browser, uploading
// to the console, etaHEN's settings, the consoles, the app's language, and About (with backup and the app's settings).
// ---------------------------------------------------------------------------------------------

/** One row of the More list: a round icon (or letter) and a title. */
@Composable
fun RoundRow(title: String, icon: ImageVector? = null, letter: String = "", enabled: Boolean = true, detail: String = "", onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(cs.surfaceContainer)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF3A0C14), cs.surface))),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = BLOOD_BRIGHT, modifier = Modifier.size(22.dp))
            else Text(letter, color = BLOOD_BRIGHT, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = if (enabled) cs.onSurface else cs.onSurfaceVariant)
            // One line on what it is for, so the list can be read without opening everything
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Text("›", color = cs.onSurfaceVariant, fontSize = 20.sp)
    }
}

/** The More screen. A row whose action is null is shown but can't be tapped (etaHEN settings without a console address). */
@Composable
fun MoreTab(
    onWeb: () -> Unit,
    onUpload: () -> Unit,
    onEtaHen: (() -> Unit)?,
    onConsoles: () -> Unit,
    onAbout: () -> Unit,
    console: Profile? = null,
    onRunChain: ((Chain) -> Unit)? = null
) {
    var showLanguage by remember { mutableStateOf(false) }
    var showDiary by remember { mutableStateOf(false) }
    var showRunning by remember { mutableStateOf(false) }
    var showMissed by remember { mutableStateOf(false) }
    if (showMissed) MissedDialog { showMissed = false }
    var showChecklist by remember { mutableStateOf(false) }
    var showSnapshots by remember { mutableStateOf(false) }
    var showAutomation by remember { mutableStateOf(false) }
    var showDev by remember { mutableStateOf(false) }
    var showBisect by remember { mutableStateOf(false) }
    var showSession by remember { mutableStateOf(false) }
    if (console != null) {
        if (showChecklist) ChecklistDialog(console) { showChecklist = false }
        if (showSnapshots) SnapshotsDialog(console) { showSnapshots = false }
        if (showAutomation) AutomationDialog(console) { showAutomation = false }
        if (showDev) DevModeDialog(console) { showDev = false }
        if (showBisect && onRunChain != null) BisectDialog(console, onRunChain) { showBisect = false }
        if (showSession) SessionDialog(console) { showSession = false }
    }
    if (showRunning && console != null) RunningDialog(console) { showRunning = false }
    if (showLanguage) LanguageDialog { showLanguage = false }
    if (showDiary) CrashDiaryDialog { showDiary = false }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(str(R.string.nav_more), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 8.dp))
        // Grouped by when you need them: every day, when something goes wrong, and setting things up
        MoreSection(str(R.string.more_sec_daily))
        RoundRow(str(R.string.nav_web), icon = WebIcon, detail = str(R.string.more_d_web), onClick = onWeb)
        RoundRow(str(R.string.nav_upload), icon = UploadIcon, detail = str(R.string.more_d_upload), onClick = onUpload)
        RoundRow(str(R.string.consoles_title), letter = "C", detail = str(R.string.more_d_consoles), onClick = onConsoles)
        RoundRow(str(R.string.running_title), letter = "●", enabled = console != null, detail = str(R.string.more_d_running), onClick = { showRunning = true })
        RoundRow(str(R.string.session_title), letter = "≡", enabled = console != null, detail = str(R.string.more_d_session), onClick = { showSession = true })
        RoundRow(str(R.string.missed_title), letter = "↻", detail = str(R.string.more_d_missed), onClick = { showMissed = true })

        MoreSection(str(R.string.more_sec_trouble))
        RoundRow(str(R.string.crashdiary_title), letter = "!", detail = str(R.string.more_d_diary), onClick = { showDiary = true })
        RoundRow(str(R.string.bisect_title), letter = "½", enabled = console != null && onRunChain != null, detail = str(R.string.more_d_bisect), onClick = { showBisect = true })
        RoundRow(str(R.string.check_title, console?.type?.model ?: ActiveConsole.type.model), letter = "✓", enabled = console != null, detail = str(R.string.more_d_check), onClick = { showChecklist = true })

        MoreSection(str(R.string.more_sec_setup))
        RoundRow(str(R.string.etahen_title), letter = "e", enabled = onEtaHen != null, detail = str(R.string.more_d_etahen), onClick = { onEtaHen?.invoke() })
        RoundRow(str(R.string.snap_title), letter = "S", enabled = console != null, detail = str(R.string.more_d_snap), onClick = { showSnapshots = true })
        RoundRow(str(R.string.auto_title), letter = "⚙", enabled = console != null, detail = str(R.string.more_d_auto), onClick = { showAutomation = true })
        RoundRow(str(R.string.dev_title), letter = "D", enabled = console != null, detail = str(R.string.more_d_dev), onClick = { showDev = true })
        RoundRow(str(R.string.language_title), letter = "A", detail = str(R.string.more_d_language), onClick = { showLanguage = true })
        RoundRow(str(R.string.about_button), letter = "i", detail = str(R.string.more_d_about), onClick = onAbout)
    }
}

/** A heading over a group of More rows. */
@Composable
private fun MoreSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = BLOOD_BRIGHT,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp)
    )
}

/** A row of pill buttons, one of them selected (red into black). */
@Composable
fun PillSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clip(CircleShape).background(cs.surfaceContainer).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .then(if (on) Modifier.background(bloodGradient) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) Color.White else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    }
}
