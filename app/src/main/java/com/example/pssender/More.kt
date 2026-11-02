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
fun RoundRow(title: String, icon: ImageVector? = null, letter: String = "", enabled: Boolean = true, onClick: () -> Unit) {
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
        Text(
            title,
            fontWeight = FontWeight.Bold,
            color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
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
    onAbout: () -> Unit
) {
    var showLanguage by remember { mutableStateOf(false) }
    var showDiary by remember { mutableStateOf(false) }
    if (showLanguage) LanguageDialog { showLanguage = false }
    if (showDiary) CrashDiaryDialog { showDiary = false }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(str(R.string.nav_more), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 8.dp))
        RoundRow(str(R.string.nav_web), icon = WebIcon, onClick = onWeb)
        RoundRow(str(R.string.nav_upload), icon = UploadIcon, onClick = onUpload)
        RoundRow(str(R.string.etahen_title), letter = "e", enabled = onEtaHen != null, onClick = { onEtaHen?.invoke() })
        RoundRow(str(R.string.consoles_title), letter = "C", onClick = onConsoles)
        RoundRow(str(R.string.crashdiary_title), letter = "!", onClick = { showDiary = true })
        RoundRow(str(R.string.language_title), letter = "A", onClick = { showLanguage = true })
        RoundRow(str(R.string.about_button), letter = "i", onClick = onAbout)
    }
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
