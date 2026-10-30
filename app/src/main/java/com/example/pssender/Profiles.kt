package com.example.pssender

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// Console profiles: each console keeps its own type (PS5 or PS4), IP, ports and FTP login/folder
// ---------------------------------------------------------------------------------------------

/** Longest name a console can be given (it has to fit in the button at the top of the screen). */
const val PROFILE_NAME_MAX = 16

/**
 * Which console a saved console is: what it is called ([model]), the homebrew enabler that runs on it ([hen]), what its
 * package installer is called in the port field ([installerName]) and the ports its usual services listen on. A PS5 runs
 * etaHEN, which brings the installer and the loader; a PS4 runs GoldHEN (loader on 9090, FTP on 2121) and installs
 * packages through flatz's Remote Package Installer (RPI) on 12800.
 */
enum class ConsoleType(
    val id: String,
    val model: String,
    val hen: String,
    val installerName: String,
    val installPort: String,
    val loaderPort: String,
    val ftpPort: String
) {
    PS5("ps5", "PS5", "etaHEN", "etaHEN", "12800", "9021", "2121"),
    PS4("ps4", "PS4", "GoldHEN", "RPI", "12800", "9090", "2121");

    companion object {
        /** The type saved as [id]: a PS5 when it is missing or unknown (consoles saved before there were types). */
        fun of(id: String?): ConsoleType = values().firstOrNull { it.id == id } ?: PS5
    }
}

/** The type of the console that is selected, for texts made where no console is at hand (help pages, error messages). */
object ActiveConsole {
    @Volatile
    var type: ConsoleType = ConsoleType.PS5

    /** The saved console's id, for things remembered per console (like its firmware). */
    @Volatile
    var id: String = ""
}

/** One saved console. Ports are kept as text because they are typed into text fields. */
data class Profile(
    val id: String,
    val name: String,
    val host: String = "",
    val installPort: String = "12800",
    val payloadPort: String = "9021",
    val ftpPort: String = "2121",
    val ftpUser: String = "anonymous",
    val ftpDir: String = "/data",
    /** Which accent color (a position in [ACCENTS]) this console uses across the app. */
    val color: Int = 0,
    /** Which console it is, a PS5 or a PS4. */
    val type: ConsoleType = ConsoleType.PS5
)

/**
 * This console as a [to]. A port that is still the old type's usual one becomes the new type's (a port typed by hand
 * stays as it is), and a name that is still the app's own first name ("My PS5") follows the type ("My PS4").
 */
fun Profile.withType(to: ConsoleType): Profile {
    if (to == type) return this
    fun swap(current: String, old: String, new: String): String = if (current.trim() == old) new else current
    val usualName = AppText.get(R.string.profile_default, type.model)
    return copy(
        type = to,
        installPort = swap(installPort, type.installPort, to.installPort),
        payloadPort = swap(payloadPort, type.loaderPort, to.loaderPort),
        ftpPort = swap(ftpPort, type.ftpPort, to.ftpPort),
        name = if (name == usualName) AppText.get(R.string.profile_default, to.model) else name
    )
}

fun newProfileId(): String = UUID.randomUUID().toString()

/** The first accent color no console uses yet, or a repeat once they are all taken. */
fun freeColor(existing: List<Profile>): Int =
    ACCENTS.indices.firstOrNull { c -> existing.none { it.color.mod(ACCENTS.size) == c } } ?: (existing.size % ACCENTS.size)

/** A new console named "Console 2", "Console 3", ... (the first free number, in the app's language) with the usual default ports. */
fun newProfile(existing: List<Profile>, id: String = newProfileId()): Profile {
    var n = existing.size + 1
    while (existing.any { it.name == AppText.get(R.string.profile_new, n) }) n++
    return Profile(id = id, name = AppText.get(R.string.profile_new, n), color = freeColor(existing))
}

fun profilesToJson(list: List<Profile>): String {
    val arr = JSONArray()
    for (p in list) {
        arr.put(
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("host", p.host)
                .put("installPort", p.installPort)
                .put("payloadPort", p.payloadPort)
                .put("ftpPort", p.ftpPort)
                .put("ftpUser", p.ftpUser)
                .put("ftpDir", p.ftpDir)
                .put("color", p.color)
                .put("type", p.type.id)
        )
    }
    return arr.toString()
}

/** Reads the saved list. Anything unreadable is skipped; null or broken input gives an empty list. */
fun parseProfiles(json: String?): List<Profile> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            if (id.isEmpty()) {
                null
            } else {
                Profile(
                    id = id,
                    name = o.optString("name", "").ifBlank { AppText.get(R.string.profile_fallback) },
                    host = o.optString("host", ""),
                    installPort = o.optString("installPort", "12800"),
                    payloadPort = o.optString("payloadPort", "9021"),
                    ftpPort = o.optString("ftpPort", "2121"),
                    ftpUser = o.optString("ftpUser", "anonymous"),
                    ftpDir = o.optString("ftpDir", "/data"),
                    // Consoles saved before colors existed get one each, in order, so they look different
                    color = o.optInt("color", i % ACCENTS.size),
                    // Consoles saved before there were types are PS5s
                    type = ConsoleType.of(o.optString("type", ""))
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** The single set of settings from before profiles existed, turned into the first profile. */
fun legacyProfile(get: (String, String) -> String, id: String = newProfileId()): Profile = Profile(
    id = id,
    name = AppText.get(R.string.profile_default, ConsoleType.PS5.model),
    host = get("host", ""),
    installPort = get("install_port", "12800"),
    payloadPort = get("payload_port", "9021"),
    ftpPort = get("ftp_port", "2121"),
    ftpUser = get("ftp_user", "anonymous"),
    ftpDir = get("ftp_dir", "/data")
)

/** Saves and loads the consoles (and which one is selected) in the app's settings. */
object ProfileStore {
    private const val PROFILES = "profiles_v1"
    private const val ACTIVE = "active_profile"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The saved consoles. The first time, the old single-console settings become the first profile. */
    fun load(ctx: Context): List<Profile> {
        val p = prefs(ctx)
        val saved = parseProfiles(p.getString(PROFILES, null))
        if (saved.isNotEmpty()) return saved
        val first = legacyProfile({ key, default -> p.getString(key, default) ?: default })
        save(ctx, listOf(first))
        return listOf(first)
    }

    fun save(ctx: Context, list: List<Profile>) {
        prefs(ctx).edit().putString(PROFILES, profilesToJson(list)).apply()
    }

    /** The id of the selected console; falls back to the first one if the saved choice is gone. */
    fun activeId(ctx: Context, list: List<Profile>): String {
        val id = prefs(ctx).getString(ACTIVE, null)
        return if (id != null && list.any { it.id == id }) id else list.first().id
    }

    fun saveActive(ctx: Context, id: String) {
        prefs(ctx).edit().putString(ACTIVE, id).apply()
    }
}

// ---------------------------------------------------------------------------------------------
// Switcher dialog
// ---------------------------------------------------------------------------------------------

/**
 * Lists the consoles; tap one to switch. Also adds, renames, recolors and deletes consoles, sets whether the selected one
 * is a PS5 or a PS4 ([onType]), and opens the language settings ([onLanguage]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsolesDialog(
    profiles: List<Profile>,
    activeId: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onColor: (Int) -> Unit,
    onType: (ConsoleType) -> Unit,
    onLanguage: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val active = profiles.firstOrNull { it.id == activeId } ?: return

    if (renaming) {
        var name by remember { mutableStateOf(active.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(str(R.string.console_rename_title)) },
            text = { Field(str(R.string.console_name_label), name) { name = it.take(PROFILE_NAME_MAX) } },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRename(name.trim())
                        renaming = false
                    }
                ) { Text(str(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(str(R.string.action_cancel)) } }
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(str(R.string.console_delete_title, active.name)) },
            text = { Text(str(R.string.console_delete_text)) },
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
        title = { Text(str(R.string.consoles_title)) },
        text = {
            // Scrolls, so a long list of consoles or a long translation never pushes the buttons out of view
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                profiles.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSwitch(p.id)
                                onClose()
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (p.id == activeId) StatusDot(accentColor(p.color), 14.dp) else StatusRing(accentColor(p.color), 14.dp)
                        Column {
                            Text(p.name)
                            Text(p.type.model + " · " + p.host.ifBlank { str(R.string.console_no_ip) }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(str(R.string.console_color_of, active.name), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACCENTS.indices.forEach { i ->
                        val picked = i == active.color.mod(ACCENTS.size)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .then(
                                    if (picked) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
                                )
                                .clickable(onClickLabel = str(R.string.console_use_color, str(ACCENTS[i].nameRes))) { onColor(i) }
                                .padding(6.dp)
                        ) {
                            Box(Modifier.fillMaxSize().background(accentColor(i), CircleShape))
                        }
                    }
                }
                // PS5 or PS4: the ports that are still the usual ones change along (see Profile.withType)
                Text(str(R.string.console_type_of, active.name), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleType.values().forEach { t ->
                        val picked = t == active.type
                        val cs = MaterialTheme.colorScheme
                        Text(
                            t.model,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (picked) cs.onPrimary else cs.onSurface,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (picked) cs.primary else cs.surfaceVariant)
                                .clickable { onType(t) }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
                // The actions wrap onto a second line when a language needs more room
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { renaming = true }) { Text(str(R.string.action_rename)) }
                    TextButton(enabled = profiles.size > 1, onClick = { deleting = true }) { Text(str(R.string.action_delete)) }
                    TextButton(onClick = onLanguage) { Text(str(R.string.language_button)) }
                    TextButton(onClick = onAbout) { Text(str(R.string.about_button)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text(str(R.string.console_add)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.action_close)) } }
    )
}
