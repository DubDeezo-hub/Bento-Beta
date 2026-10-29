package com.example.pssender // change to match your project's package name

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// The welcome page: on the first start, a short rundown of what Bento is, what each tab does, what is needed and
// how to begin. It can be opened again from the About window.
// ---------------------------------------------------------------------------------------------

object WelcomeState {
    private const val SEEN = "welcome_seen_v1"

    /** Whether the welcome page is on screen. */
    var showing by mutableStateOf(false)

    /** Shows the page if this is the first start. */
    fun check(ctx: Context) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!p.getBoolean(SEEN, false)) showing = true
    }

    fun done(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(SEEN, true).apply()
        showing = false
    }
}

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().background(cs.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(88.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(132.dp))
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(132.dp))
            }
            Text(str(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(str(R.string.welcome_intro), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = cs.onSurfaceVariant)

            WelcomeCard(str(R.string.welcome_need_title)) {
                Text(str(R.string.welcome_need), style = MaterialTheme.typography.bodyMedium)
            }

            WelcomeCard(str(R.string.welcome_tabs_title)) {
                WelcomeTab(InstallIcon, R.string.nav_install, R.string.welcome_tab_install)
                WelcomeTab(PayloadIcon, R.string.nav_payload, R.string.welcome_tab_payload)
                WelcomeTab(UploadIcon, R.string.nav_upload, R.string.welcome_tab_upload)
                WelcomeTab(FilesIcon, R.string.nav_files, R.string.welcome_tab_files)
                WelcomeTab(WebIcon, R.string.nav_web, R.string.welcome_tab_web)
                WelcomeTab(MemoryIcon, R.string.nav_memory, R.string.welcome_tab_memory)
            }

            WelcomeCard(str(R.string.welcome_start_title)) {
                WelcomeStep(1, R.string.welcome_step1)
                WelcomeStep(2, R.string.welcome_step2)
                WelcomeStep(3, R.string.welcome_step3)
            }

            Text(str(R.string.welcome_again), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        // Always on screen, however long the page is
        Button(
            onClick = onDone,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
        ) { Text(str(R.string.welcome_go)) }
    }
}

@Composable
private fun WelcomeCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun WelcomeTab(icon: ImageVector, name: Int, what: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(str(name), style = MaterialTheme.typography.labelLarge)
            Text(str(what), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WelcomeStep(n: Int, text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(n.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Text(str(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
