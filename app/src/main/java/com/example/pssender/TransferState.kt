package com.example.pssender // change to match your project's package name

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Browser-like user agent for downloads that don't come with one (for example links shared from other apps). */
const val FALLBACK_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/**
 * One FTP transfer. An upload sends either a picked file ([fileUri]) or a web link that the phone downloads and
 * relays ([url]) into the folder [dir]. A download (when [remotePath] is set) copies that file from the console
 * to the phone's Downloads/PS5 folder; [remoteSize] is the size from the folder listing, or -1 when unknown. For a
 * download [dir] names the folders inside Downloads/PS5 that it goes into ("Games/Sub"); it is empty for Downloads/PS5 itself.
 * [console] is the name of the console it was started for (shown on the transfer card), [profileId] which saved console
 * that is (a transfer picked up again after the app was closed finds the console's address of the day by it).
 */
data class TransferRequest(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val dir: String,
    val fileUri: String? = null,
    val url: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    val remotePath: String? = null,
    val remoteSize: Long = -1L,
    val console: String = "",
    val profileId: String = ""
)

/**
 * What the screen shows about the current (or most recent) transfer. [total] is -1 when the size is unknown.
 * [ok] is true only for a transfer that finished successfully (it is false while running and after a failure).
 * [endedAt] is the time it ended (milliseconds since 1970), or 0 while it has not ended.
 * [speed] is bytes per second (0 while not known) and [secondsLeft] the time left (-1 while not known).
 * [status] is a line about what is going on besides moving bytes (for example that the connection broke and a new try
 * is coming). [parked] means the transfer failed and the queue waits for the user: try again, or skip it.
 * [paused] means the user holds the running transfer (it carries on when resumed).
 * [completed] counts the transfers that worked since the app started.
 * When a run of several transfers is over, [label] and [message] hold the summary of the whole run instead.
 */
data class TransferInfo(
    val active: Boolean = false,
    val label: String = "",
    val done: Long = 0L,
    val total: Long = -1L,
    val message: String = "",
    val download: Boolean = false,
    val ok: Boolean = false,
    val endedAt: Long = 0L,
    val console: String = "",
    val speed: Long = 0L,
    val secondsLeft: Long = -1L,
    val status: String = "",
    val parked: Boolean = false,
    val canRetry: Boolean = false,
    val completed: Int = 0,
    val paused: Boolean = false
)

/**
 * Holds the queue of transfers, what the transfer card shows, and the switches for pausing and cancelling. It is also
 * the [TransferHooks] the engine reports to. The app uses the single instance [TransferState]; the tests make their own
 * (with their own [clock], milliseconds since 1970, to see how long a run took).
 */
class TransferCenter(clock: () -> Long = { System.currentTimeMillis() }) : TransferHooks {
    val info = MutableStateFlow(TransferInfo())

    /** The transfers that wait for their turn (the running one is not in it). */
    val queue = TransferQueue<TransferRequest>()

    @Volatile
    var cancelRequested = false

    /** Closes the live connections of the running transfer. Set and cleared by the engine. */
    @Volatile
    var abortHook: (() -> Unit)? = null

    private val memories = HashMap<Long, ResumeMemory>()
    private val meter = SpeedMeter()

    /** True while the user holds the queue: the running transfer waits where it stopped. */
    private val pausedState = MutableStateFlow(false)

    /** The speed limit in bytes per second (0: none). */
    private val limitState = MutableStateFlow(0L)

    /** What the current run of transfers amounts to so far. */
    private val tally = RunTally(clock)

    /**
     * Hears about every change to the list of unfinished transfers (the one that runs, the one that failed, the ones that
     * wait, and the ones left from the last run of the app), so it can be kept for the next start. None: nothing is kept.
     */
    @Volatile
    var journal: TransferJournal? = null

    /** Held while the queue, the running transfer or the left-overs change, so that every list handed to the [journal] is whole. */
    private val listLock = Any()

    /** The transfer the worker has taken and not finished yet. */
    private var current: TransferRequest? = null

    /** True once the service was cut short from outside: the transfer it was on is not lost, it waits for the user's decision. */
    @Volatile
    private var interrupted = false

    /** The transfers from the last run of the app, waiting for the user to continue or discard them. */
    private val unfinishedState = MutableStateFlow<List<TransferRequest>>(emptyList())

    /** The list the [journal] was handed last. */
    private var lastRecorded: List<TransferRequest>? = null

    // ----- called from the screen -----

    /** Puts a transfer at the end of the queue. Returns true when the transfer service has to be started. */
    fun enqueue(req: TransferRequest): Boolean =
        synchronized(listLock) {
            val start = enqueueLocked(req)
            record()
            start
        }

    private fun enqueueLocked(req: TransferRequest): Boolean {
        val start = queue.add(req)
        if (start) {
            tally.start() // nothing was going on: this is a new run
            showStarting(req)
        }
        return start
    }

    /** The failed transfer is tried again. Returns true when the transfer service has to be started. */
    fun retry(): Boolean {
        val start = synchronized(listLock) { queue.retryParked() }
        if (start) queue.waiting.value.firstOrNull()?.let { showStarting(it.item) }
        return start
    }

    /** The failed transfer is dropped and the queue goes on. Returns true when the transfer service has to be started. */
    fun skip(): Boolean {
        val failed = queue.parked
        val start = synchronized(listLock) {
            queue.skipParked().also { record() }
        }
        if (failed != null) {
            discard(failed.id, true)
            tally.transferFailed()
        }
        if (start) {
            queue.waiting.value.firstOrNull()?.let { showStarting(it.item) }
        } else if (!info.value.active) {
            // Nothing is left: the run is over. A run of several transfers says how it went as a whole
            val summary = tally.summary().takeIf { it.worthShowing }
            val title = summary?.let { summaryTitle(it) }.orEmpty()
            val detail = summary?.let { summaryDetail(it) }.orEmpty()
            info.update {
                if (summary == null) {
                    TransferInfo(completed = it.completed)
                } else {
                    TransferInfo(
                        label = title, message = detail, ok = summary.allGood, endedAt = System.currentTimeMillis(),
                        console = it.console, completed = it.completed
                    )
                }
            }
        }
        return start
    }

    /** Takes one waiting transfer out of the queue. */
    fun removeFromQueue(id: Long) {
        val removed = synchronized(listLock) {
            queue.remove(id).also { if (it) record() }
        }
        if (removed) discard(id, true)
    }

    /** Drops everything that waits (and the failed transfer, if any). The running transfer carries on. */
    fun clearQueue() {
        val dropped = synchronized(listLock) {
            queue.clear().also { record() }
        }
        for (d in dropped) discard(d.id, true)
        if (!info.value.active) info.update { TransferInfo(completed = it.completed) }
    }

    // ----- what was left from the last run of the app -----

    /** The transfers from the last run of the app that wait for the user to continue or discard them. */
    val unfinished: StateFlow<List<TransferRequest>> get() = unfinishedState

    /**
     * Called once when the app starts, with what the last run left in its file. They wait for [continueUnfinished] or
     * [discardUnfinished]; until then they stay on the list that is kept, behind everything queued meanwhile.
     */
    fun adoptUnfinished(list: List<TransferRequest>) {
        synchronized(listLock) {
            unfinishedState.value = list.take(UNFINISHED_MAX)
            lastRecorded = snapshot() // the file holds exactly this already
        }
    }

    /** The left-overs are dropped for good. */
    fun discardUnfinished() {
        synchronized(listLock) {
            unfinishedState.value = emptyList()
            record()
        }
    }

    /**
     * Puts the left-overs at the end of the queue. [usable] says whether one can still be done (a picked file may be gone;
     * it may read files, so it is asked before the lists are touched) and [refresh] brings one up to date (the console's
     * address of the day, the password of this session). One that is in the queue already, or running, is not queued
     * again. The left-overs are over after this, also the ones that could not be taken up.
     */
    fun continueUnfinished(usable: (TransferRequest) -> Boolean, refresh: (TransferRequest) -> TransferRequest): ContinueResult {
        val saved = unfinishedState.value
        val ready = saved.map { usable(it) }
        val fresh = saved.map { refresh(it) }
        synchronized(listLock) {
            var queued = 0
            var missing = 0
            var start = false
            for (i in saved.indices) {
                if (isListed(fresh[i])) continue
                if (!ready[i]) {
                    missing++
                    continue
                }
                if (enqueueLocked(fresh[i])) start = true
                queued++
            }
            unfinishedState.value = emptyList()
            record()
            return ContinueResult(queued, missing, start)
        }
    }

    /** True when [req] is the same work as something that runs, failed and waits, or waits its turn. Call with [listLock] held. */
    private fun isListed(req: TransferRequest): Boolean =
        current?.let { sameTransfer(it, req) } == true ||
            queue.parked?.let { sameTransfer(it.item, req) } == true ||
            queue.waiting.value.any { sameTransfer(it.item, req) }

    /** Everything unfinished, in the order it would be done in. Call with [listLock] held. */
    private fun snapshot(): List<TransferRequest> {
        val now = ArrayList<TransferRequest>()
        current?.let { now.add(it) }
        queue.parked?.let { now.add(it.item) }
        for (e in queue.waiting.value) now.add(e.item)
        now.addAll(unfinishedState.value)
        return now.take(UNFINISHED_MAX)
    }

    /** Hands the [journal] what is unfinished now, unless that is what it was handed last. Call with [listLock] held. */
    private fun record() {
        val now = snapshot()
        if (now == lastRecorded) return
        lastRecorded = now
        journal?.record(now)
    }

    /** Hides the result of a finished transfer. */
    fun clear() {
        val now = info.value
        if (!now.active && !now.parked) info.update { TransferInfo(completed = it.completed) }
    }

    /**
     * Holds the running transfer. It lets go of its connections where it is and waits; [resumeTransfer] lets it carry on
     * from the same place. Returns false when nothing is running or it is held already.
     */
    fun pauseTransfer(): Boolean {
        if (!info.value.active || pausedState.value) return false
        pausedState.value = true
        tally.paused()
        info.update { it.copy(paused = true, speed = 0L, secondsLeft = -1L, status = "") }
        closeConnections() // a transfer that is stuck waiting on the network stops at once, too
        return true
    }

    /** Lets a held transfer carry on. Returns false when nothing was held. */
    fun resumeTransfer(): Boolean {
        if (!pausedState.value) return false
        tally.resumed()
        info.update { it.copy(paused = false, status = AppText.get(R.string.transfer_reconnecting)) }
        pausedState.value = false
        return true
    }

    /** Worker: returns once the transfer is no longer held (at once when it isn't). Cancelling also ends the hold. */
    suspend fun waitUntilResumed() {
        pausedState.first { !it }
    }

    /**
     * Asks the running transfer to stop. Also closes its connections so a stalled transfer ends at once. A held transfer
     * is let go of, so it can see the cancel and end.
     */
    fun requestCancel() {
        cancelRequested = true
        if (pausedState.value) {
            tally.resumed()
            info.update { it.copy(paused = false) }
            pausedState.value = false
        }
        closeConnections()
    }

    /** The speed limit changed (0: no limit). The running transfer follows at its next piece of data. */
    fun setSpeedLimit(bytesPerSecond: Long) {
        limitState.value = bytesPerSecond.coerceAtLeast(0L)
    }

    /** The speed limit in bytes per second (0: none), for the screen. */
    val limit: StateFlow<Long> get() = limitState

    /** The service could not be started: tell the screen, and let the next transfer ask for it again. */
    fun workerFailedToStart(message: String) {
        queue.workerFailedToStart()
        endHold()
        info.update { ended(it, message, false) }
    }

    // ----- called by the service's worker -----

    /** The next transfer to run, or null when nothing is left (the worker then stops). */
    fun takeNext(): QueueEntry<TransferRequest>? =
        synchronized(listLock) {
            queue.takeNext().also { current = it?.item } // it stays on the list of unfinished work while it runs
        }

    /** [entry] starts now. */
    fun begin(entry: QueueEntry<TransferRequest>) {
        cancelRequested = false
        interrupted = false
        val req = entry.item
        info.update {
            TransferInfo(
                active = true,
                label = req.name,
                message = AppText.get(R.string.transfer_starting),
                download = req.remotePath != null,
                console = req.console,
                completed = it.completed,
                paused = pausedState.value
            )
        }
    }

    /** What the engine remembers about [id] between tries. */
    fun memoryFor(id: Long): ResumeMemory = synchronized(memories) { memories.getOrPut(id) { ResumeMemory() } }

    /**
     * [entry] ended with [result]. Returns true when the worker should go on with the next transfer, and false when it
     * should stop because the transfer failed and the queue waits for the user. When the last transfer of a run of
     * several ends, the card shows how the run went as a whole.
     */
    fun finishItem(entry: QueueEntry<TransferRequest>, result: TransferResult): Boolean {
        val more = queue.waitingCount > 0
        if (result.ok) {
            leaveList()
            discard(entry.id, false)
            tally.transferDone(info.value.done)
            val end = if (more) null else runEnd()
            info.update {
                val counted = it.copy(completed = it.completed + 1)
                if (more) counted else ended(counted, result.message, true).withSummary(end)
            }
            return true
        }
        val cutShort = interrupted
        if (result.cancelled && !cutShort) {
            leaveList()
            discard(entry.id, false)
            tally.transferStopped()
            val end = if (more) null else runEnd()
            if (!more) info.update { ended(it, result.message, false).withSummary(end) }
            return true
        }
        // Failed, or cut short because the service went away: it waits for the user's decision, and stays on the list
        synchronized(listLock) {
            queue.park(entry)
            current = null
            interrupted = false
            record()
        }
        endHold()
        val text = if (cutShort) AppText.get(R.string.transfer_stopped) else result.message
        info.update { ended(it, text, false).copy(parked = true, canRetry = result.retry || cutShort) }
        return false
    }

    /** The worker is through with its transfer: it is no longer unfinished work. */
    private fun leaveList() {
        synchronized(listLock) {
            current = null
            record()
        }
    }

    /**
     * The service is going away. The transfer it was on is not lost: it waits for the user's decision (try again, or skip)
     * and stays on the list that is kept for the next start of the app.
     */
    fun stopped() {
        if (info.value.active) {
            interrupted = true
            requestCancel()
            info.update { ended(it, AppText.get(R.string.transfer_stopped), false) }
        }
    }

    // ----- the engine's hooks -----

    override val cancelled: Boolean get() = cancelRequested

    override val paused: Boolean get() = pausedState.value

    override val speedLimit: Long get() = limitState.value

    override fun starting(done: Long, total: Long) {
        meter.reset(done)
        info.update { it.copy(done = done, total = total, speed = 0L, secondsLeft = -1L) }
    }

    override fun progress(done: Long, total: Long) {
        meter.add(done)
        val speed = meter.bytesPerSecond()
        val left = SpeedMeter.secondsLeft(done, total, speed)
        info.update { it.copy(done = done, total = total, speed = speed, secondsLeft = left, status = "") }
    }

    override fun status(text: String) {
        info.update { it.copy(status = text) }
    }

    override fun setAbort(hook: (() -> Unit)?) {
        abortHook = hook
    }

    // ----- inside -----

    private fun showStarting(req: TransferRequest) {
        info.update {
            TransferInfo(
                active = true,
                label = req.name,
                message = AppText.get(R.string.transfer_starting),
                download = req.remotePath != null,
                console = req.console,
                completed = it.completed,
                paused = pausedState.value
            )
        }
    }

    private fun ended(from: TransferInfo, message: String, ok: Boolean): TransferInfo =
        from.copy(
            active = false, message = message, ok = ok, endedAt = System.currentTimeMillis(),
            speed = 0L, secondsLeft = -1L, status = "", parked = false, canRetry = false, paused = false
        )

    /** The summary of the run that just ended, or null when it was a single transfer. A hold ends with the run. */
    private fun runEnd(): RunSummary? {
        endHold()
        return tally.summary().takeIf { it.worthShowing }
    }

    /** The card with the summary of a run instead of the last transfer's own message. */
    private fun TransferInfo.withSummary(s: RunSummary?): TransferInfo =
        if (s == null) this else copy(label = summaryTitle(s), message = summaryDetail(s), ok = s.allGood)

    /** The hold on the queue is over (the run ended, or something failed): the next transfer starts free. */
    private fun endHold() {
        if (pausedState.value) {
            tally.resumed()
            pausedState.value = false
        }
    }

    /** Runs the hook that closes the running transfer's connections, off the caller's thread. */
    private fun closeConnections() {
        val hook = abortHook
        if (hook != null) {
            Thread {
                try {
                    hook()
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    /** Forgets what the engine remembered about [id] and cleans up after it (for example deletes a half-written download). */
    private fun discard(id: Long, inBackground: Boolean) {
        val memory = synchronized(memories) { memories.remove(id) } ?: return
        val job = memory.discard ?: return
        memory.discard = null
        if (inBackground) {
            Thread {
                try {
                    job()
                } catch (_: Exception) {
                }
            }.start()
        } else {
            try {
                job()
            } catch (_: Exception) {
            }
        }
    }
}

/** The one transfer center of the app, shared by the screen and the transfer service (both run in the same process). */
val TransferState = TransferCenter()

/**
 * Runs one transfer to its end ([run] does a go at it) across the user's pauses. When the transfer comes back paused it
 * lets go of the phone ([letGo]: the wake locks), waits until the pause is over without holding any thread, takes hold
 * again ([takeHold]) and runs the same transfer once more, which continues from where it stopped. A transfer that is
 * cancelled while it waits, or before it runs at all, ends right here without running.
 */
suspend fun runAcrossPauses(
    center: TransferCenter,
    letGo: () -> Unit,
    takeHold: () -> Unit,
    run: () -> TransferResult
): TransferResult {
    var stoppedText = AppText.get(R.string.transfer_stopped)
    while (true) {
        if (center.paused) {
            letGo()
            try {
                center.waitUntilResumed()
            } finally {
                takeHold()
            }
        }
        if (center.cancelRequested) return TransferResult(false, stoppedText, cancelled = true)
        val result: TransferResult = try {
            run()
        } catch (e: Exception) {
            TransferResult(false, describeFailure(e), retry = isTransientFailure(e))
        }
        if (!result.paused) return result
        stoppedText = result.message
    }
}

/** "3.2 MB/s, 4:05 left": what is known about how fast the running transfer goes (empty while nothing is known yet). */
fun speedAndTime(t: TransferInfo): String = speedAndTime(t.speed, t.secondsLeft)

/** The same from a speed in bytes per second (0: not known) and the seconds left (-1: not known). */
fun speedAndTime(speed: Long, secondsLeft: Long): String {
    val parts = ArrayList<String>()
    if (speed > 0L) parts.add(fmtRate(speed))
    if (secondsLeft >= 0L) parts.add(AppText.get(R.string.transfer_eta, fmtDuration(secondsLeft)))
    return joinList(parts)
}
