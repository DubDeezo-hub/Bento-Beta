package com.example.pssender

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/** The console's answer to an install request: [ok] says whether it accepted the request, [text] is the message to show. */
class InstallReply(val ok: Boolean, val text: String)

/** One file to share from the phone: [name] as the person knows it, [size] in bytes, and where its bytes come from. */
class ServeItem(val name: String, val size: Long, val source: ByteSource)

/** What to share, and which console is told to install it ([host] and [installerPort] are where its installer listens). */
class ServeRequest(val consoleName: String, val host: String, val installerPort: String, val items: List<ServeItem>)

/**
 * What the screen shows about one shared file. [covered] is how many of its bytes the console has taken, [url] the link
 * (empty until the server is up), and [installOk] whether the console accepted the install request (null: no answer yet)
 * with [installText] as the message that goes with it.
 */
data class ServeFileInfo(
    val name: String,
    val size: Long,
    val url: String = "",
    val covered: Long = 0L,
    val done: Boolean = false,
    val problem: Boolean = false,
    val installOk: Boolean? = null,
    val installText: String = ""
)

/**
 * What the screen shows about sharing. [running] is true from the moment the person taps Start until sharing ends;
 * [message] says why sharing is not running when that is worth saying (empty after a normal stop).
 * [speed] is bytes per second over all files and [secondsLeft] the time until the console has everything (-1: not known).
 */
data class ServeInfo(
    val running: Boolean = false,
    val consoleName: String = "",
    val files: List<ServeFileInfo> = emptyList(),
    val speed: Long = 0L,
    val secondsLeft: Long = -1L,
    val message: String = ""
)

/** The parts of the outside world that sharing uses. The app uses the defaults; the tests replace them. */
class ServeEnv(
    val addresses: () -> List<LocalAddress> = ::localAddresses,
    val route: (Inet4Address) -> Inet4Address? = ::routeSource,
    val install: suspend (host: String, port: String, url: String) -> InstallReply = ::installRequest,
    val preferredPort: Int = FileServer.PREFERRED_PORT,
    val idleMillis: Long = SERVE_IDLE_MS
)

/**
 * Shares files from the phone with a console: starts the file server, tells the console's installer to install from the
 * links, and publishes how far the console got. The app uses the single instance [ServeState]; the tests make their own.
 * Only one sharing session runs at a time.
 */
class ServeCenter {
    val info = MutableStateFlow(ServeInfo())

    private val lock = Any()
    private var pending: ServeRequest? = null
    private var busy = false
    private var stopSignal = CompletableDeferred<Unit>()
    private var generation = 0

    // ----- called from the screen -----

    /**
     * The person wants to share [request]. Returns false when something is being shared already; otherwise the screen
     * shows the files at once and the caller has to start [run] (through the service).
     */
    fun prepare(request: ServeRequest): Boolean {
        synchronized(lock) {
            if (busy) return false
            busy = true
            pending = request
            stopSignal = CompletableDeferred()
            generation++
            info.value = ServeInfo(
                running = true,
                consoleName = request.consoleName,
                files = request.items.map { ServeFileInfo(it.name, it.size) }
            )
            return true
        }
    }

    /** The service could not be started after [prepare]: nothing is shared. */
    fun prepareFailed(message: String) {
        synchronized(lock) {
            pending = null
            busy = false
            generation++
            info.value = ServeInfo(message = message)
        }
    }

    /** Ends sharing (or does nothing when nothing is shared). */
    fun stop() {
        val signal = synchronized(lock) { stopSignal }
        signal.complete(Unit)
    }

    // ----- called by the service -----

    /** The request that [prepare] was given, handed over once. */
    fun takePending(): ServeRequest? = synchronized(lock) { pending.also { pending = null } }

    /** Shares [request] until [stop] is called, the server stops by itself, or the calling coroutine is cancelled. */
    suspend fun run(request: ServeRequest, env: ServeEnv = ServeEnv()) {
        val gen: Int
        val signal: CompletableDeferred<Unit>
        synchronized(lock) {
            gen = generation
            signal = stopSignal
        }
        var message = ""
        try {
            message = share(request, env, gen, signal)
        } finally {
            synchronized(lock) {
                busy = false
                if (generation == gen) info.value = ServeInfo(message = message)
            }
        }
    }

    // ----- inside -----

    /** Does the sharing. Returns the message to leave on the screen afterwards (empty: stopped normally). */
    private suspend fun share(request: ServeRequest, env: ServeEnv, gen: Int, signal: CompletableDeferred<Unit>): String {
        if (signal.isCompleted || request.items.isEmpty()) return ""
        val address = chooseServeAddress(request.host, env.addresses(), env.route)
            ?: return AppText.get(R.string.serve_no_wifi)

        val shared = request.items.map { SharedFile(newToken(), safeLinkName(it.name), it.size, it.source) }
        val taken = LongArray(shared.size) // how many bytes of each file the console has taken
        val totalSize = shared.sumOf { it.size }
        val meter = SpeedMeter()
        val idle = AtomicBoolean(false)

        // Changes the published state, unless this sharing is over and another one has begun
        fun change(edit: (ServeInfo) -> ServeInfo) {
            synchronized(lock) {
                if (generation == gen) info.value = edit(info.value)
            }
        }

        fun changeFile(index: Int, edit: (ServeFileInfo) -> ServeFileInfo) {
            change { cur -> cur.copy(files = cur.files.mapIndexed { i, f -> if (i == index) edit(f) else f }) }
        }

        /** Takes a speed reading from the bytes the console has taken so far (also called while nothing moves). */
        fun publishSpeed() {
            synchronized(lock) {
                if (generation != gen) return
                val done = taken.sum()
                meter.add(done)
                val speed = meter.bytesPerSecond()
                val left = SpeedMeter.secondsLeft(done, totalSize, speed)
                info.value = info.value.copy(speed = speed, secondsLeft = left)
            }
        }

        val events = object : ServerEvents {
            override fun progress(file: SharedFile, covered: Long) {
                val index = shared.indexOf(file)
                if (index < 0) return
                synchronized(lock) { taken[index] = covered }
                changeFile(index) { f -> f.copy(covered = covered, problem = if (covered > f.covered) false else f.problem) }
                publishSpeed()
            }

            override fun completed(file: SharedFile) {
                val index = shared.indexOf(file)
                if (index >= 0) changeFile(index) { it.copy(done = true) }
            }

            override fun problem(file: SharedFile?, message: String) {
                val index = if (file == null) -1 else shared.indexOf(file)
                if (index >= 0) changeFile(index) { it.copy(problem = true) }
            }

            override fun idleStop() {
                idle.set(true)
                signal.complete(Unit)
            }
        }

        val server = FileServer(shared, events, idleMillis = env.idleMillis)
        val port = try {
            server.start(address, env.preferredPort)
        } catch (e: Exception) {
            return AppText.get(R.string.serve_failed, e.message ?: e.javaClass.simpleName)
        }
        val urls = shared.map { "http://" + address.hostAddress + ":" + port + "/" + it.token + "/" + it.name }
        change { cur -> cur.copy(files = cur.files.mapIndexed { i, f -> f.copy(url = urls[i]) }) }

        // The helpers run beside the server so that a stop is never held up by a slow answer from the console
        val helpers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        helpers.launch {
            for (i in urls.indices) {
                ensureActive()
                val reply = env.install(request.host, request.installerPort, urls[i])
                ensureActive()
                changeFile(i) { it.copy(installOk = reply.ok, installText = reply.text) }
            }
        }
        helpers.launch {
            while (isActive) {
                delay(1000L)
                publishSpeed()
            }
        }
        try {
            signal.await()
        } finally {
            helpers.cancel()
            server.stop()
        }
        return if (idle.get()) AppText.get(R.string.serve_idle_stop) else ""
    }
}

/** The one sharing center of the app, shared by the screen and the sharing service (both run in the same process). */
val ServeState = ServeCenter()
