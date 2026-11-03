package com.example.pssender

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val TAB_HOME = 0
internal const val TAB_PAYLOADS = 1
internal const val TAB_FILES = 2
internal const val TAB_MEMORY = 3
internal const val TAB_MORE = 4

/** A screen lower than this (in dp) counts as short: a phone held sideways. */
internal const val SHORT_SCREEN_DP = 480

/** From this width on (a tablet), the tabs go down the left side and the transfers get a pane on the right. */
internal const val WIDE_SCREEN_DP = 840
internal const val SIDE_PANE_DP = 360

/** How often the console card rechecks the console while the app is on screen. */
internal const val CHECK_EVERY_MS = 6000L

/** How long the result of a successful transfer stays above the bottom bar before it hides itself. */
internal const val RESULT_VISIBLE_MS = 15000L

/** How long the automatic chain waits after the loader comes up, so the jailbreak can finish. */
internal const val AUTO_CHAIN_DELAY_MS = 3000L

/** A transfer that ended longer ago than this no longer buzzes (for example when you open the app much later). */
internal const val BUZZ_WINDOW_MS = 3000L

/**
 * The five places, in the order of the bottom bar; the position is the tab number used below. Installing from links is
 * on Home, uploading in Files, and the web browser in More.
 */
internal val NAV_ITEMS = listOf(
    NavItem(R.string.nav_home, HomeIcon),
    NavItem(R.string.nav_payload, PayloadIcon),
    NavItem(R.string.nav_files, FilesIcon),
    NavItem(R.string.nav_memory, MemoryIcon),
    NavItem(R.string.nav_more, MoreIcon)
)

class MainActivity : ComponentActivity() {
    /** A link another app shared to us; the screen consumes it and sets it back to null. */
    private var sharedLink by mutableStateOf<String?>(null)

    /** The id of a chain that was asked for from the app icon (press and hold) or the home screen; the screen consumes it. */
    private var chainRequest by mutableStateOf<String?>(null)

    /**
     * True while the app is on screen. The console is only checked then, so nothing runs in the background.
     * A plain flag on purpose: the screen stops drawing while the app is hidden, so it reads this directly.
     */
    private var onScreen = false

    /** Goes up each time the app comes on screen, which makes the screen start its console checks again. */
    private var timesShown by mutableStateOf(0)

    /** On Android 12 and older, the language picked in the app (newer versions apply it themselves). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReports.install(this, appVersion(this).name) // a crash from here on is noted, and offered on the next start
        OnlineAlert.schedule(this) // the "console is online" check, while any console has it on
        TransferState.setSpeedLimit(SpeedLimits.load(this)) // the saved speed limit applies from the first transfer on
        TransferState.lanes = FilesAtOnce.load(this)
        PayloadFirmware.load(this)
        // Only on a fresh start: after a rotation the same intent would otherwise be handled twice
        if (savedInstanceState == null) {
            readShare(intent)
            readChainRequest(intent)
        }
        // The logo opening plays on a fresh start only, not when the screen is rebuilt (the phone turned)
        val fresh = savedInstanceState == null
        setContent {
            IntroHost(play = fresh) {
                SenderApp(sharedLink, { sharedLink = null }, timesShown, chainRequest, { chainRequest = null }) { onScreen }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        onScreen = true
        ScreenState.onScreen = true
        timesShown++
    }

    override fun onStop() {
        onScreen = false
        ScreenState.onScreen = false
        super.onStop()
    }

    // The app is already open when something is shared to it
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)
        readChainRequest(intent)
    }

    private fun readChainRequest(source: Intent?) {
        val i = source ?: return
        if (i.action != ACTION_RUN_CHAIN) return
        chainRequest = i.getStringExtra(EXTRA_CHAIN_ID)
        i.action = null // used up: coming back to the app later must not start the chain again
    }

    private fun readShare(source: Intent?) {
        val i = source ?: return
        if (i.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        sharedLink = Regex("""https?://\S+""").find(text)?.value
    }
}

/** A text setting that is loaded from, and saved to, SharedPreferences whenever it changes. */
@Composable
fun rememberPref(key: String, default: String): MutableState<String> {
    val prefs = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val state = remember { mutableStateOf(prefs.getString(key, default) ?: default) }
    LaunchedEffect(state.value) { prefs.edit().putString(key, state.value).apply() }
    return state
}

/** Reads a saved setting without composing, for use inside button handlers. */
fun readPref(ctx: Context, key: String, default: String): String =
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(key, default) ?: default

/** A short vibration when a transfer ends: a "confirm" tick for success and a "reject" buzz for a failure. */
internal fun buzz(view: View, ok: Boolean) {
    if (AppLook.buzzOn) {
        val kind = when {
            Build.VERSION.SDK_INT < 30 -> HapticFeedbackConstants.LONG_PRESS
            ok -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.REJECT
        }
        view.performHapticFeedback(kind)
    }
    if (AppLook.soundOn) {
        // The phone's own short tones: a rising one when it went, a low one when it didn't (at notification volume)
        try {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(if (ok) android.media.ToneGenerator.TONE_PROP_ACK else android.media.ToneGenerator.TONE_PROP_NACK, 200)
            view.postDelayed({ tone.release() }, 400)
        } catch (_: Exception) {
        }
    }
}

/**
 * The whole app screen. [timesShown] changes every time the app comes back on screen (so the console checks restart),
 * and [isShown] tells right now whether it is on screen (so they stop when it is hidden).
 */
@Composable
fun SenderApp(
    sharedLink: String? = null,
    onSharedConsumed: () -> Unit = {},
    timesShown: Int = 1,
    chainRequest: String? = null,
    onChainConsumed: () -> Unit = {},
    isShown: () -> Boolean = { true }
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(ProfileStore.load(ctx)) }
    var activeId by remember { mutableStateOf(ProfileStore.activeId(ctx, profiles)) }
    var passwords by remember { mutableStateOf(mapOf<String, String>()) } // FTP passwords: not saved, kept for this session only
    var homeUrl by rememberPref("home_url_v2", DEFAULT_HOME)
    var tab by rememberSaveable { mutableStateOf(0) } // the open tab; it stays when the phone is turned or the screen is rebuilt
    if (tab !in NAV_ITEMS.indices) tab = TAB_HOME // a number kept from before the bar had five places
    var webOpen by rememberSaveable { mutableStateOf(false) } // the web browser, opened from More
    var filesUpload by rememberSaveable { mutableStateOf(false) } // Files shows the upload screen instead of the folders
    var savedTabs by rememberSaveable { mutableStateOf("") } // the open browser tabs, written as text; they come back when the screen is rebuilt
    var linkMenu by remember { mutableStateOf<String?>(null) } // the web address of a link that was pressed for a while in the Web tab
    var pending by remember { mutableStateOf<PendingDownload?>(null) }
    var offSiteUrl by remember { mutableStateOf<String?>(null) }
    var statusLine by remember { mutableStateOf("") }
    var showConsoles by remember { mutableStateOf(false) }
    var showAddress by remember { mutableStateOf(false) }
    var showDiagnose by remember { mutableStateOf(false) }
    var showStore by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showEtaHen by remember { mutableStateOf(false) }
    var showFind by rememberSaveable { mutableStateOf(false) } // the "Find my PS5" window; it scans again when the screen is rebuilt
    val findAsked by FindConsole.asked.collectAsState()
    var shelfVersion by remember { mutableStateOf(0) }
    var chains by remember { mutableStateOf(ChainStore.load(ctx)) }
    // A web download on its way to the console (started, arrived, didn't): said in the status line too
    val notice by ConsoleNotices.latest.collectAsState()
    // A payload update or a switch back to its earlier version renames it in the saved chains: show them as saved
    val chainsChanged by ChainStore.changed.collectAsState()
    LaunchedEffect(chainsChanged) { if (chainsChanged > 0) chains = ChainStore.load(ctx) }
    val chainState by ChainRunner.state.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    var showLimit by remember { mutableStateOf(false) }
    val speedLimit by TransferState.limit.collectAsState()
    var consoleStatus by remember { mutableStateOf<ConsoleStatus?>(null) }
    var wasActive by remember { mutableStateOf(false) }
    val version = remember { appVersion(ctx) }
    // A crash last time: offered once, to share or put away
    var crashReport by remember { mutableStateOf(CrashReports.pending(ctx)) }
    var update by remember { mutableStateOf(UpdatePrefs.waiting(ctx, version.code)) } // a newer version found earlier
    var showAbout by remember { mutableStateOf(false) }
    // A phone on its side has little height: the console card shrinks to one line and the update banner waits, so the tab and the bottom bar keep their room
    val shortScreen = LocalConfiguration.current.screenHeightDp < SHORT_SCREEN_DP
    // Wide enough for the tabs on the side and the transfers in a pane of their own
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP && !shortScreen
    var installLinks by rememberSaveable { mutableStateOf("") } // what is typed in the Install box; kept when the tab changes
    val changeInstallLinks: ((String) -> String) -> Unit = { change -> installLinks = change(installLinks) }
    val files = remember { FilesModel() }
    val transfer by TransferState.info.collectAsState()
    val waiting by TransferState.queue.waiting.collectAsState()
    val leftovers by TransferState.unfinished.collectAsState() // transfers the last run of the app did not finish
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Android 17: reaching the console needs the "local network" permission; refused, the status line says why
    val askLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) statusLine = AppText.get(R.string.local_network_refused)
    }
    // One browser for the whole session, so the pages survive switching tabs; its own tabs come back from savedTabs after a rebuild
    val web = remember {
        allowedSites.add(siteOf(Uri.parse(homeUrl).host))
        makeWebBrowser(ctx, { homeUrl }, savedTabs, { offSiteUrl = it }, { pending = it }, { linkMenu = it }, { savedTabs = it })
    }
    val browser = web.model
    DisposableEffect(web) { onDispose { browser.destroy() } }

    // The selected console. Everything below reads its IP, ports and FTP login from here.
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    // Texts made where no console is at hand (help pages, error messages) name the selected console's type
    SideEffect {
        ActiveConsole.type = active.type
        ActiveConsole.id = active.id
        PayloadFirmware.consoleFirmware = ConsoleFirmware.number(ConsoleFirmware.get(ctx, active.id))
    }
    // The debugger tells us the console's firmware; the store uses it to say which payloads fit
    val seenFirmware = MemorySession.snapshot?.info?.firmware.orEmpty()
    // New payloads and newer versions are looked for once each time the app starts (and again for another console type)
    LaunchedEffect(active.type) { StoreUpdates.check(ctx, active.type) }
    LaunchedEffect(seenFirmware, active.id) {
        if (seenFirmware.isNotEmpty() && seenFirmware != "?" && MemorySession.host == active.host.trim()) ConsoleFirmware.set(ctx, active.id, seenFirmware)
    }
    val host = active.host
    val ftpPass = passwords[active.id] ?: ""
    val setFtpPass: (String) -> Unit = { passwords = passwords + (activeId to it) }
    val updateActive: ((Profile) -> Profile) -> Unit = { change ->
        profiles = profiles.map { if (it.id == activeId) change(it) else it }
        ProfileStore.save(ctx, profiles)
    }
    val ftpSettings: FtpSettings? = active.ftpPort.trim().toIntOrNull()?.let { port ->
        if (active.host.isBlank()) null else FtpSettings(active.host.trim(), port, active.ftpUser.trim(), ftpPass, active.type.model)
    }
    SideEffect { ActiveConsole.ftp = ftpSettings }
    val switchConsole: (String) -> Unit = { id ->
        if (id != activeId) {
            activeId = id
            ProfileStore.saveActive(ctx, id)
            statusLine = ""
            files.reset()
        }
    }

    // What the console card checks: the three services of the selected console
    val installerName = str(R.string.service_installer)
    val loaderName = str(R.string.service_loader)
    val ftpName = str(R.string.service_ftp)
    val target = ConsoleTarget(
        host.trim(),
        listOf(
            installerName to active.installPort.trim().toIntOrNull(),
            loaderName to active.payloadPort.trim().toIntOrNull(),
            ftpName to active.ftpPort.trim().toIntOrNull()
        )
    )
    val level = consoleLevel(target, consoleStatus)
    // The loader's last known state, to notice it coming up (a jailbreak just ran) for the console's automatic chain
    var loaderWasUp by remember(target) { mutableStateOf<Boolean?>(null) }
    // "Tell me when it comes online" for this console (the address dialog has the switch)
    var onlineOn by remember(active.id) { mutableStateOf(OnlineAlert.isOn(ctx, active.id)) }
    // Since when this console's loader answers (the last jailbreak), for Home
    var upSince by remember(active.id) { mutableStateOf(UpSince.load(ctx, active.id)) }
    val checks = consoleStatus?.takeIf { it.target == target }?.checks ?: pendingChecks(target)

    // Checks the console now and then, but only while the app is on screen and an address is set. The loop ends
    // by itself soon after the app is hidden, and a new one starts when it comes back (timesShown changes).
    LaunchedEffect(target, timesShown) {
        if (target.host.isEmpty()) return@LaunchedEffect
        delay(300) // typing an address or a port shouldn't start a check for every key
        while (isShown()) {
            // Not while a transfer runs: the console is busy with it, and the transfer card shows how it goes
            if (!TransferState.info.value.active) consoleStatus = checkConsole(target)
            delay(CHECK_EVERY_MS)
        }
    }

    // Another screen (the help page of an unreachable console) asked for the "Find my PS5" window
    LaunchedEffect(findAsked) {
        if (findAsked) {
            showFind = true
            FindConsole.handled()
        }
    }

    // Asks GitHub whether a newer version exists, at most once a day and only when the switch in About is on.
    // A failed check isn't recorded, so the next time the app opens it asks again.
    LaunchedEffect(timesShown) {
        if (updateDue(UpdatePrefs.enabled(ctx), UpdatePrefs.lastCheck(ctx), System.currentTimeMillis())) {
            val result = withContext(Dispatchers.IO) { checkForUpdate(version.code) }
            UpdatePrefs.record(ctx, result, System.currentTimeMillis())
            update = UpdatePrefs.waiting(ctx, version.code)
        }
    }

    // A short buzz when a transfer ends while you are looking at the app (not when you come back long after)
    LaunchedEffect(transfer.active) {
        val justEnded = System.currentTimeMillis() - transfer.endedAt < BUZZ_WINDOW_MS
        if (wasActive && !transfer.active && transfer.label.isNotEmpty() && justEnded && isShown()) buzz(view, transfer.ok)
        wasActive = transfer.active
    }

    // A finished transfer that worked hides itself after a while on screen; a failed one stays until dismissed
    LaunchedEffect(transfer.active, transfer.ok, transfer.label, timesShown) {
        if (!transfer.active && transfer.ok && transfer.label.isNotEmpty() && isShown()) {
            delay(RESULT_VISIBLE_MS)
            if (isShown()) TransferState.clear()
        }
    }

    // A link shared from another app goes through the same chooser as a download from the Web tab
    LaunchedEffect(sharedLink) {
        if (sharedLink != null) {
            pending = PendingDownload(sharedLink, FALLBACK_UA, null, null)
            onSharedConsumed()
        }
    }

    // Android 13 and newer need the person's permission to show the notification of a background job
    val askNotificationsIfNeeded: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Puts FTP transfers in the queue (they run one at a time in the background) and asks for notification permission
    // when it is still missing (once, however many there are). A transfer remembers which console it was started for.
    val startTransfers: (List<TransferRequest>) -> Unit = { reqs ->
        if (reqs.isNotEmpty()) {
            askNotificationsIfNeeded()
            var startsNow = false
            for (req in reqs) startsNow = TransferService.enqueue(ctx, req.copy(console = active.name, profileId = active.id, model = active.type.model))
            statusLine = if (startsNow) "" else AppText.get(R.string.msg_queued, TransferState.queue.waitingCount)
        }
    }
    val startTransfer: (TransferRequest) -> Unit = { req -> startTransfers(listOf(req)) }

    // Transfers the last run of the app did not finish: Continue puts them back in the queue, each one up to date (the
    // console's address of the day, this session's password, the browser's cookie for a link). Whether a picked file can
    // still be read is looked at off the main thread; what cannot be found is said in the status line.
    val continueLeftovers: () -> Unit = {
        askNotificationsIfNeeded()
        val saved = leftovers
        scope.launch {
            val readable = withContext(Dispatchers.IO) {
                saved.associateWith { r -> r.fileUri == null || canReadPicked(ctx.contentResolver, Uri.parse(r.fileUri)) }
            }
            val result = TransferService.continueUnfinished(
                ctx,
                usable = { r -> readable[r] ?: true },
                refresh = { r -> refreshSaved(r, profiles, passwords) { link -> CookieManager.getInstance().getCookie(link) } }
            )
            if (result.missing > 0) statusLine = AppText.get(R.string.unfinished_missing, result.missing)
        }
    }

    // Payload chains: the service sends the payloads of a chain and keeps its pauses; the saved list and the shortcuts on
    // the app icon are kept in step with each other
    val runChain: (Chain) -> Unit = { chain ->
        askNotificationsIfNeeded()
        val onShelf = shelfList(shelfDir(ctx)).map { it.name }.toSet()
        if (ChainRunner.request(chain, host, active.payloadPort, active.name, onShelf)) ChainService.start(ctx)
    }
    // A chain that would load etaHEN, kstuff or GoldHEN a second time waits for "Send anyway"
    var reloadAsk by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val runChainChecked: (Chain) -> Unit = { chain ->
        scope.launch {
            val names = chain.steps.map { it.payload }
            // A bad load order (checked here, on the names) or a second load of a running payload both wait for "Send anyway"
            val why = ChainOrder.check(names, active.type) ?: withContext(Dispatchers.IO) { ReloadGuard.warning(host, names, active.type) }
            if (why == null) runChain(chain) else reloadAsk = why to { runChain(chain) }
        }
    }
    val saveChains: (List<Chain>, List<String>) -> Unit = { list, removed ->
        chains = list
        ChainStore.save(ctx, list)
        ChainShortcuts.publish(ctx, list, removed)
    }

    LaunchedEffect(notice) { notice?.let { if (System.currentTimeMillis() - it.at < 60_000) statusLine = it.text } }

    // When the loader starts answering after it didn't, the console's automatic chain (if any) runs
    LaunchedEffect(consoleStatus) {
        val s = consoleStatus ?: return@LaunchedEffect
        if (s.target != target) return@LaunchedEffect
        val up = s.checks.firstOrNull { it.label == loaderName }?.up ?: return@LaunchedEffect
        val was = loaderWasUp
        loaderWasUp = up
        // The open app keeps the "online" state current, so the background check only speaks up about a real change
        OnlineAlert.note(ctx, active, up || s.checks.firstOrNull { it.label == ftpName }?.up == true, notify = false)
        upSince = UpSince.update(ctx, active.id, was, up)
        val chainId = AutoChain.get(ctx, active.id)
        if (chainId.isEmpty() || chainState.active) return@LaunchedEffect
        if (AutoChain.due(active.id, was, up, ctx = ctx)) {
            val chain = chains.firstOrNull { it.id == chainId } ?: return@LaunchedEffect
            delay(AUTO_CHAIN_DELAY_MS) // let the loader settle after the jailbreak
            // A bad load order, or something that already loaded etaHEN (an autoloader on the console): the chain doesn't run
            val names = chain.steps.map { it.payload }
            val why = ChainOrder.check(names, active.type) ?: withContext(Dispatchers.IO) { ReloadGuard.warning(host, names, active.type) }
            tab = 1
            if (why == null) runChain(chain) else statusLine = AppText.get(R.string.reload_auto_skipped, why)
        }
    }

    // The weekly backup, when it is switched on (About)
    LaunchedEffect(Unit) {
        val where = withContext(Dispatchers.IO) { try { AutoBackup.runIfDue(ctx, version.name) } catch (_: Exception) { null } }
        if (where != null) statusLine = AppText.get(R.string.backup_auto_done, where)
    }

    // New screenshots and clips go to the phone by themselves, once per start, when the console's FTP answers (if on)
    var capturesChecked by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(consoleStatus) {
        val s = consoleStatus ?: return@LaunchedEffect
        if (s.target != target) return@LaunchedEffect
        val settings = ftpSettings ?: return@LaunchedEffect
        if (s.checks.firstOrNull { it.label == ftpName }?.up != true) return@LaunchedEffect
        if (active.id in capturesChecked || !CaptureSync.autoOn(ctx, active.id)) return@LaunchedEffect
        capturesChecked = capturesChecked + active.id
        val plan = withContext(Dispatchers.IO) {
            try { CaptureSync.plan(ctx, settings, active.id, firstTimeAll = false) } catch (_: Exception) { emptyList() }
        }
        if (plan.isNotEmpty()) {
            startTransfers(plan.map {
                TransferRequest(
                    name = it.name, host = settings.host, port = settings.port, user = settings.user, pass = settings.pass,
                    dir = it.saveIn, remotePath = it.remotePath, remoteSize = it.size
                )
            })
            CaptureSync.markCopied(ctx, active.id, plan.map { it.remotePath })
        }
    }

    // The shortcuts on the app icon follow the saved chains (a restore rebuilds the screen, so they follow that, too)
    LaunchedEffect(Unit) { ChainShortcuts.publish(ctx, chains) }
    LaunchedEffect(Unit) { WelcomeState.check(ctx) }
    // Black box: the console log starts by itself for the selected console (and reconnects after restarts)
    LaunchedEffect(active.id, active.host) {
        if (BlackBox.on && active.host.isNotBlank() && !KernelLog.running) {
            KernelLog.attach(ctx)
            KernelLog.start(active.host.trim(), active.name)
        }
    }
    // The shared compatibility list, ready for the check before sending (read at most once a day)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { try { CompatData.get(ctx) } catch (_: Exception) {} } }
    LaunchedEffect(Unit) { if (!LocalNetwork.allowed(ctx)) askLocalNetwork.launch(LocalNetwork.PERMISSION) }
    // The saved address doesn't answer at the start: look for the console on the Wi-Fi network and offer what answers
    var autoFound by remember { mutableStateOf<AutoFound?>(null) }
    LaunchedEffect(Unit) {
        val p = active
        if (p.host.isBlank()) return@LaunchedEffect
        delay(AUTO_FIND_DELAY_MS) // the app's first checks go first
        val others = profiles.filter { it.id != p.id }.map { it.host.trim() }.toSet()
        val found = withContext(Dispatchers.IO) {
            if (!LocalNetwork.allowed(ctx) || OnlineAlert.answers(p)) return@withContext null
            val nets = scanNetworks(localAddresses(), ::routeSource)
            if (nets.isEmpty()) null else autoPick(scanWifi(nets, scanPortsFor(p)), p.host, others)
        }
        if (found != null) autoFound = AutoFound(p.id, p.host.trim(), found)
    }

    // A chain asked for from the app icon or the home screen: show the Payload tab and start it
    LaunchedEffect(chainRequest) {
        val id = chainRequest
        if (id != null) {
            tab = 1
            val chain = ChainStore.load(ctx).firstOrNull { it.id == id }
            if (chain == null) ChainRunner.rejected(AppText.get(R.string.chain_err_unknown)) else runChainChecked(chain)
            onChainConsumed()
        }
    }

    // A chain that worked hides itself after a while on screen; one that failed stays until dismissed
    LaunchedEffect(chainState.active, chainState.ok, chainState.message, timesShown) {
        if (!chainState.active && chainState.ok && chainState.message.isNotEmpty() && isShown()) {
            delay(RESULT_VISIBLE_MS)
            if (isShown()) ChainRunner.dismiss()
        }
    }

    val onWeb = tab == TAB_MORE && webOpen
    // Back goes back in the browser first, then closes it (to More)
    BackHandler(enabled = onWeb) { if (browser.backNeeded) browser.back() else webOpen = false }

    AppTheme(active.color) {
        // The red glow behind every screen (plain black with the black look on)
        Surface(modifier = Modifier.fillMaxSize().background(pageBackground(AppLook.black)), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(active.name, accentColor(active.color), onAbout = { showAbout = true }) { showConsoles = true }
                update?.takeIf { !shortScreen }?.let { u ->
                    UpdateBanner(
                        release = u,
                        onDownload = { openLink(ctx, u.apkUrl ?: u.pageUrl) },
                        onLater = {
                            UpdatePrefs.dismiss(ctx, u.tag)
                            update = null
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    )
                }
                // Changing places: the new screen slides in from the side it lies on in the bar (right when going right)
                // and fades up, in about a quarter of a second
                val enter = remember { Animatable(1f) }
                var lastPlace by remember { mutableStateOf(tab) }
                var slideFrom by remember { mutableStateOf(0f) }
                var firstPlace by remember { mutableStateOf(true) }
                LaunchedEffect(tab, onWeb) {
                    // The first screen comes in with the opening (Intro.kt); only later changes slide
                    if (firstPlace) {
                        firstPlace = false
                        return@LaunchedEffect
                    }
                    slideFrom = when {
                        tab > lastPlace -> 1f
                        tab < lastPlace -> -1f
                        else -> 0f
                    }
                    lastPlace = tab
                    enter.snapTo(0f)
                    enter.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
                }
                // A tablet (or a big phone on its side) gets the tabs down the left side and the transfers on the right
                val mainPane: @Composable ColumnScope.() -> Unit = {
                    // Home shows the console in its ring, and the web page and More need the room: the card is left out there
                    if (tab != TAB_HOME && tab != TAB_MORE) ConsoleCard(
                        host = host.trim(),
                        level = level,
                        checks = checks,
                        compact = true,
                        onEdit = { showAddress = true },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onFind = { showFind = true },
                        type = active.type,
                        onCheck = { showDiagnose = true }
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer {
                                val left = 1f - enter.value
                                alpha = 1f - left * 0.85f
                                translationX = slideFrom * left * 28.dp.toPx()
                                translationY = (if (slideFrom == 0f) left else 0f) * 18.dp.toPx()
                            }
                            .padding(start = 16.dp, top = if (onWeb) 4.dp else 12.dp, end = 16.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Files: the console's folders, or uploading to it
                        if (tab == TAB_FILES) {
                            PillSwitch(listOf(str(R.string.files_view_browse), str(R.string.nav_upload)), if (filesUpload) 1 else 0) { filesUpload = it == 1 }
                        }
                        when {
                            onWeb -> WebTab(
                                browser, web.container, homeUrl,
                                { allowedSites.add(siteOf(Uri.parse(it).host)); homeUrl = it },
                                Modifier.weight(1f)
                            )
                            tab == TAB_FILES && !filesUpload -> FilesTab(
                                model = files,
                                scope = scope,
                                settings = ftpSettings,
                                profile = active,
                                pass = ftpPass,
                                onPassChange = setFtpPass,
                                onUpdateProfile = updateActive,
                                transferActive = transfer.active,
                                onStart = startTransfer,
                                onStartMany = startTransfers,
                                modifier = Modifier.weight(1f)
                            )
                            tab == TAB_MEMORY -> MemoryTab(host, active.name, Modifier.weight(1f))
                            else -> {
                                // The form tabs scroll, so nothing is cut off on small screens
                                Column(
                                    // Each place keeps its own scroll position (one shared position left More scrolled
                                    // past its first rows after scrolling down on Home)
                                    modifier = Modifier.weight(1f).verticalScroll(
                                        rememberSaveable(tab, filesUpload, saver = ScrollState.Saver) { ScrollState(0) }
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    when (tab) {
                                        TAB_HOME -> HomeTab(
                                            host = host, level = level, checks = checks, type = active.type,
                                            loaderPort = active.payloadPort, ftp = ftpSettings,
                                            onEdit = { showAddress = true }, onFind = { showFind = true }, onCheck = { showDiagnose = true },
                                            onOpenPayloads = { tab = TAB_PAYLOADS },
                                            shelfVersion = shelfVersion,
                                            upLine = UpSince.text(upSince)
                                        ) {
                                            InstallTab(host, active.installPort, { v -> updateActive { it.copy(installPort = v) } }, active.name, installLinks, changeInstallLinks, askNotificationsIfNeeded, active.type, ftpSettings)
                                        }
                                        TAB_MORE -> MoreTab(
                                            onWeb = { webOpen = true },
                                            onUpload = { filesUpload = true; tab = TAB_FILES },
                                            onEtaHen = if (ftpSettings != null) ({ showEtaHen = true }) else null,
                                            onConsoles = { showConsoles = true },
                                            onAbout = { showAbout = true },
                                            console = active
                                        )
                                        TAB_PAYLOADS -> PayloadTab(
                                            host, active.payloadPort, { v -> updateActive { it.copy(payloadPort = v) } }, shelfVersion,
                                            chains = chains,
                                            chainBusy = chainState.active,
                                            onRunChain = runChainChecked,
                                            onSaveChain = { c ->
                                                saveChains(if (chains.any { it.id == c.id }) chains.map { if (it.id == c.id) c else it } else chains + c, emptyList())
                                            },
                                            onDeleteChain = { c -> saveChains(chains.filter { it.id != c.id }, listOf(c.id)) },
                                            onPinChain = { c -> if (!ChainShortcuts.pin(ctx, c)) statusLine = AppText.get(R.string.chain_pin_unsupported) }
                                        )
                                        else -> UploadTab(host, active, updateActive, ftpPass, setFtpPass, startTransfer, ftpSettings, startTransfers)
                                    }
                                }
                            }
                        }
                    }
                }
                val cards: @Composable ColumnScope.() -> Unit = {
                    if (statusLine.isNotEmpty()) {
                        StatusStrip(statusLine) { statusLine = "" }
                    }
                    UnfinishedCard(leftovers, onContinue = continueLeftovers, onDiscard = { TransferState.discardUnfinished() })
                    ChainCard(chainState, onStop = { ChainRunner.requestStop() }, onDismiss = { ChainRunner.dismiss() })
                    TransferCard(
                        transfer, waiting.size, speedLimit,
                        onCancel = { TransferState.requestCancel() },
                        onPauseResume = { if (transfer.paused) TransferState.resumeTransfer() else TransferState.pauseTransfer() },
                        onLimit = { showLimit = true },
                        onDismiss = { TransferState.clear() },
                        onRetry = { TransferService.retry(ctx) },
                        onSkip = { TransferService.skip(ctx) },
                        onQueue = { showQueue = true }
                    )
                }
                if (wide) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        SideRail(NAV_ITEMS, tab) { tab = it }
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) { mainPane() }
                        Column(
                            modifier = Modifier.width(SIDE_PANE_DP.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 12.dp, end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(str(R.string.side_activity), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                            cards()
                            Text(
                                str(R.string.side_activity_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                } else {
                    mainPane()
                    cards()
                    BottomBar(NAV_ITEMS, tab) { tab = it }
                }
            }

            // The welcome page, on the first start (and from the About window)
            if (WelcomeState.showing) {
                BackHandler { WelcomeState.done(ctx) }
                WelcomeScreen { WelcomeState.done(ctx) }
            }

            if (showConsoles) {
                ConsolesDialog(
                    profiles = profiles,
                    activeId = active.id,
                    onSwitch = switchConsole,
                    onAdd = {
                        val p = newProfile(profiles)
                        profiles = profiles + p
                        ProfileStore.save(ctx, profiles)
                        switchConsole(p.id)
                        showConsoles = false
                    },
                    onRename = { name -> updateActive { it.copy(name = name) } },
                    onDelete = {
                        val remaining = profiles.filter { it.id != activeId }
                        if (remaining.isNotEmpty()) {
                            profiles = remaining
                            ProfileStore.save(ctx, remaining)
                            switchConsole(remaining.first().id)
                        }
                    },
                    onColor = { c -> updateActive { it.copy(color = c) } },
                    onType = { t -> updateActive { it.withType(t) } },
                    onLanguage = {
                        showConsoles = false
                        showLanguage = true
                    },
                    onAbout = {
                        showConsoles = false
                        showAbout = true
                    },
                    onClose = { showConsoles = false }
                )
            }

            if (showAbout) {
                AboutDialog(
                    version = version,
                    consoleCount = profiles.size,
                    lastMessage = if (statusLine.isNotEmpty()) statusLine else if (transfer.label.isNotEmpty() && !transfer.ok) transfer.message else "",
                    onUpdateFound = { update = it },
                    onClose = { showAbout = false }
                )
            }

            if (showQueue) {
                QueueDialog(
                    items = waiting,
                    onRemove = { TransferState.removeFromQueue(it) },
                    onClear = { TransferState.clearQueue() },
                    onClose = { showQueue = false }
                )
            }

            if (showLimit) {
                SpeedLimitDialog(onClose = { showLimit = false })
            }

            if (showAddress) {
                ConsoleEditDialog(
                    host = host,
                    onHostChange = { v -> updateActive { it.copy(host = v) } },
                    onFind = {
                        showAddress = false
                        showFind = true
                    },
                    onClose = { showAddress = false },
                    type = active.type,
                    onCheck = {
                        showAddress = false
                        showDiagnose = true
                    },
                    alertOn = onlineOn,
                    onAlert = { on ->
                        onlineOn = on
                        OnlineAlert.set(ctx, active.id, on)
                        if (on) askNotificationsIfNeeded()
                    }
                )
            }

            if (showDiagnose) {
                DiagnoseDialog(
                    profile = active,
                    debuggerPort = MemorySession.port.trim().toIntOrNull(),
                    onFix = { fix ->
                        when (fix) {
                            is DiagFix.UsePort -> updateActive {
                                when (fix.service) {
                                    DiagService.INSTALLER -> it.copy(installPort = fix.port.toString())
                                    DiagService.LOADER -> it.copy(payloadPort = fix.port.toString())
                                    DiagService.FTP -> it.copy(ftpPort = fix.port.toString())
                                    DiagService.DEBUGGER -> it
                                }
                            }
                            DiagFix.EtaHenSettings -> {
                                showDiagnose = false
                                showEtaHen = true
                            }
                            DiagFix.GetPayloads -> {
                                showDiagnose = false
                                showStore = true
                            }
                            DiagFix.FindConsole -> {
                                showDiagnose = false
                                showFind = true
                            }
                        }
                    },
                    onClose = { showDiagnose = false }
                )
            }

            if (showLanguage) LanguageDialog(onClose = { showLanguage = false })
            if (showEtaHen && ftpSettings != null) EtaHenSettingsDialog(ftpSettings) { showEtaHen = false }
            crashReport?.let { report ->
                AlertDialog(
                    onDismissRequest = { CrashReports.clear(ctx); crashReport = null },
                    title = { Text(str(R.string.crash_title)) },
                    text = { Text(str(R.string.crash_text)) },
                    confirmButton = {
                        TextButton(onClick = {
                            shareText(ctx, report, AppText.get(R.string.crash_title))
                            CrashReports.clear(ctx)
                            crashReport = null
                        }) { Text(str(R.string.crash_share)) }
                    },
                    dismissButton = { TextButton(onClick = { CrashReports.clear(ctx); crashReport = null }) { Text(str(R.string.action_close)) } }
                )
            }
            autoFound?.let { f ->
                if (f.profileId == active.id && host.trim() == f.saved) {
                    AlertDialog(
                        onDismissRequest = { autoFound = null },
                        title = { Text(str(R.string.autofind_title, active.type.model)) },
                        text = { Text(str(R.string.autofind_text, active.name, f.saved, f.found)) },
                        confirmButton = {
                            TextButton(onClick = {
                                updateActive { it.copy(host = f.found) }
                                statusLine = AppText.get(R.string.find_updated, active.name, f.found)
                                autoFound = null
                            }) { Text(str(R.string.action_use)) }
                        },
                        dismissButton = { TextButton(onClick = { autoFound = null }) { Text(str(R.string.autofind_keep)) } }
                    )
                }
            }
            reloadAsk?.let { (why, go) ->
                AlertDialog(
                    onDismissRequest = { reloadAsk = null },
                    title = { Text(str(R.string.send_check_title)) },
                    text = { Text(why) },
                    confirmButton = { TextButton(onClick = { reloadAsk = null; go() }) { Text(str(R.string.reload_send_anyway)) } },
                    dismissButton = { TextButton(onClick = { reloadAsk = null }) { Text(str(R.string.action_cancel)) } }
                )
            }
            if (showStore) {
                StoreDialog(type = active.type, onAdded = {}, onClose = {
                    showStore = false
                    StoreUpdates.seen()
                })
            }

            if (showFind) {
                FindConsoleDialog(
                    consoleName = active.name,
                    savedHost = host.trim(),
                    ports = scanPortsFor(active),
                    otherConsoles = profiles.filter { it.id != active.id && it.host.isNotBlank() }.associate { it.host.trim() to it.name },
                    onUse = { found ->
                        updateActive { it.copy(host = found) }
                        statusLine = AppText.get(R.string.find_updated, active.name, found)
                        showFind = false
                    },
                    onClose = { showFind = false },
                    type = active.type
                )
            }

            offSiteUrl?.let { u ->
                val site = siteOf(Uri.parse(u).host)
                AlertDialog(
                    onDismissRequest = { offSiteUrl = null },
                    title = { Text(str(R.string.offsite_title)) },
                    text = { Text(str(R.string.offsite_text, site)) },
                    confirmButton = {
                        TextButton(onClick = {
                            allowedSites.add(site)
                            offSiteUrl = null
                            browser.load(u)
                        }) { Text(str(R.string.action_open)) }
                    },
                    dismissButton = { TextButton(onClick = { offSiteUrl = null }) { Text(str(R.string.action_block)) } }
                )
            }

            // A link pressed for a while in the Web tab: open it here or in a new tab, copy it, or send or download it
            linkMenu?.let { u ->
                LinkMenuDialog(
                    url = u,
                    onOpen = {
                        allowedSites.add(siteOf(Uri.parse(u).host)) // the person chose it, so it is not asked about again
                        browser.load(u)
                    },
                    onNewTab = {
                        allowedSites.add(siteOf(Uri.parse(u).host))
                        browser.openNew(u)
                    },
                    onCopy = { copyText(ctx, "link", u, AppText.get(R.string.web_link_copied)) },
                    onUse = { pending = PendingDownload(u, browser.userAgent(), null, null) },
                    onClose = { linkMenu = null }
                )
            }

            pending?.let { d ->
                val name = URLUtil.guessFileName(d.url, d.contentDisposition, d.mimeType)

                AlertDialog(
                    onDismissRequest = { pending = null },
                    title = { Text(name) },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    pending = null
                                    if (host.isBlank()) {
                                        statusLine = AppText.get(R.string.msg_set_ip_first)
                                    } else {
                                        statusLine = AppText.get(R.string.msg_sending_link, active.type.model)
                                        scope.launch {
                                            val reply = installRequest(host.trim(), active.installPort.trim(), d.url)
                                            statusLine = reply.text
                                            if (reply.ok) {
                                                RecentLinkStore.push(ctx, listOf(d.url))
                                                ConsoleNotices.installSent(ctx, name, active.type.model, reply.text)
                                            }
                                        }
                                    }
                                }
                            ) { Text(str(R.string.choose_install, active.type.model)) }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    pending = null
                                    val port = active.ftpPort.trim().toIntOrNull()
                                    if (host.isBlank() || port == null) {
                                        statusLine = AppText.get(R.string.msg_check_ip_ftp)
                                    } else {
                                        startTransfer(
                                            TransferRequest(
                                                name = name,
                                                host = host.trim(),
                                                port = port,
                                                user = active.ftpUser.trim(),
                                                pass = ftpPass,
                                                dir = active.ftpDir.trim(),
                                                url = d.url,
                                                userAgent = d.userAgent,
                                                cookie = CookieManager.getInstance().getCookie(d.url)
                                            )
                                        )
                                    }
                                }
                            ) { Text(str(R.string.choose_ftp, active.type.model)) }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    pending = null
                                    statusLine = AppText.get(R.string.msg_adding_to_shelf_link)
                                    val cookie = CookieManager.getInstance().getCookie(d.url)
                                    scope.launch {
                                        statusLine = shelfAddFromUrl(ctx, d.url, d.userAgent, cookie, name)
                                        shelfVersion++
                                    }
                                }
                            ) { Text(str(R.string.choose_shelf)) }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    pending = null
                                    saveToPhone(ctx, d)
                                }
                            ) { Text(str(R.string.choose_phone)) }
                            Text(str(R.string.choose_help, fmtBytes(SHELF_MAX_BYTES)), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { pending = null }) { Text(str(R.string.action_cancel)) } }
                )
            }
        }
    }
}
