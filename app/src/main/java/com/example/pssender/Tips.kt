package com.example.pssender // change to match your project's package name

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// Small "?" buttons next to the options. Each opens a short how-to: what the option is for and how to use it.
// Also a section that folds away (settings that are set once and then left alone), so the tabs stay uncluttered.
// ---------------------------------------------------------------------------------------------

/** Every how-to: its title and its text. */
enum class Tip(val title: Int, val body: Int) {
    CONSOLE(R.string.tip_console_title, R.string.tip_console),
    INSTALL_LINKS(R.string.tip_install_links_title, R.string.tip_install_links),
    INSTALL_PORT(R.string.tip_install_port_title, R.string.tip_install_port),
    INSTALL_PHONE(R.string.tip_install_phone_title, R.string.tip_install_phone),
    PAYLOAD_PORT(R.string.tip_payload_port_title, R.string.tip_payload_port),
    PAYLOAD_SHELF(R.string.tip_payload_shelf_title, R.string.tip_payload_shelf),
    PAYLOAD_CHAINS(R.string.tip_payload_chains_title, R.string.tip_payload_chains),
    FTP(R.string.tip_ftp_title, R.string.tip_ftp),
    UPLOAD(R.string.tip_upload_title, R.string.tip_upload),
    FILES(R.string.tip_files_title, R.string.tip_files),
    WEB(R.string.tip_web_title, R.string.tip_web),
    WEB_HOME(R.string.tip_web_home_title, R.string.tip_web_home),
    WEB_ADBLOCK(R.string.tip_web_adblock_title, R.string.tip_web_adblock),
    WEB_DESKTOP(R.string.tip_web_desktop_title, R.string.tip_web_desktop),
    MEM_DEBUGGER(R.string.tip_mem_debugger_title, R.string.tip_mem_debugger),
    MEM_VALUE_TYPE(R.string.tip_mem_value_type_title, R.string.tip_mem_value_type),
    MEM_SCAN_TYPE(R.string.tip_mem_scan_type_title, R.string.tip_mem_scan_type),
    MEM_SKIP_SLOW(R.string.tip_mem_skip_slow_title, R.string.tip_mem_skip_slow),
    MEM_RESULTS(R.string.tip_mem_results_title, R.string.tip_mem_results),
    MEM_WATCH(R.string.tip_mem_watch_title, R.string.tip_mem_watch),
    MEM_CHEAT_FILES(R.string.tip_mem_cheat_files_title, R.string.tip_mem_cheat_files),
    MEM_MY_CHEATS(R.string.tip_mem_my_cheats_title, R.string.tip_mem_my_cheats),
    MEM_FREEZE(R.string.tip_mem_freeze_title, R.string.tip_mem_freeze),
    MEM_FIND_AGAIN(R.string.tip_mem_find_again_title, R.string.tip_mem_find_again),
    MEM_BROWSE(R.string.tip_mem_browse_title, R.string.tip_mem_browse)
}

/** A small round "?" that opens [tip]. */
@Composable
fun TipButton(tip: Tip, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val label = str(R.string.tip_button)
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(cs.primary.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = { open = true })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text("?", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = cs.primary)
    }
    if (open) TipDialog(tip) { open = false }
}

@Composable
fun TipDialog(tip: Tip, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(tip.title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(str(tip.body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(str(R.string.tip_got_it)) } }
    )
}

/** A heading with its "?" next to it. */
@Composable
fun TipTitle(text: String, tip: Tip, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleSmall) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = style)
        TipButton(tip)
    }
}

/** A field with its "?" at the end of the row. */
@Composable
fun TipField(label: String, value: String, tip: Tip, number: Boolean = false, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            if (number) NumberField(label, value, onChange) else Field(label, value, onChange)
        }
        TipButton(tip)
    }
}

/**
 * Settings that fold away: a row with [title] (and a "?" for [tip]) that opens and closes [content]. Closed at
 * first, so a tab shows what is used every time; the state is kept while the app runs.
 */
@Composable
fun Foldable(title: String, tip: Tip? = null, summary: String = "", startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = { open = !open })
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (open) "▾" else "▸", color = cs.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                if (summary.isNotEmpty() && !open) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
            if (tip != null) TipButton(tip)
        }
        if (open) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { content() }
        }
    }
}
