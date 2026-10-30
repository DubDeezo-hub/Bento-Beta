package com.example.pssender

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// Payload chains: several payloads from the shelf, sent one after the other with a pause after each. A chain is made
// once and then started with one tap, from the Payload tab or from the app icon (press and hold; see ChainShortcuts.kt).
// ---------------------------------------------------------------------------------------------

/** Longest name a chain can be given (it is the label of the shortcut on the app icon, too). */
const val CHAIN_NAME_MAX = 24

/** Most payloads in one chain. */
const val CHAIN_MAX_STEPS = 12

/** Longest pause after a payload, in seconds. */
const val CHAIN_MAX_WAIT = 300

/** Most chains that are kept. */
const val CHAINS_MAX = 20

/** The pause a payload gets when it is added to a chain, in seconds. */
const val CHAIN_DEFAULT_WAIT = 3

/** How long a payload that finds the console's loader closed is tried again (the previous payload may still be starting it), in seconds. */
const val CHAIN_CONNECT_RETRY_SECONDS = 15

/** One payload of a chain: its file name on the shelf, and the pause after it before the next payload goes (the last one's is not used). */
data class ChainStep(val payload: String, val waitSeconds: Int = 0)

/** A named list of payloads that are sent in order. */
data class Chain(val id: String, val name: String, val steps: List<ChainStep>)

fun newChainId(): String = UUID.randomUUID().toString()

/** A pause as typed: only digits count, at most [CHAIN_MAX_WAIT]. Nothing typed is no pause. */
fun parseWait(text: String): Int {
    val digits = text.filter { it in '0'..'9' }.take(4)
    return (digits.toIntOrNull() ?: 0).coerceIn(0, CHAIN_MAX_WAIT)
}

/** The seconds the chain waits in all: the pauses between its payloads (the pause after the last one is not used). */
fun chainPauseSeconds(chain: Chain): Int = chain.steps.dropLast(1).sumOf { it.waitSeconds }

/** The payloads of [chain] that are not among the [available] names on the shelf, once each, in the order of the chain. */
fun missingPayloads(chain: Chain, available: Set<String>): List<String> =
    chain.steps.map { it.payload }.filter { it !in available }.distinct()

/** "Payloads: 3 · Pauses: 0:25": what a chain is, in one line. */
fun chainSummary(chain: Chain): String {
    val parts = ArrayList<String>()
    parts.add(AppText.get(R.string.chain_summary_steps, chain.steps.size))
    val pause = chainPauseSeconds(chain)
    if (pause > 0) parts.add(AppText.get(R.string.chain_summary_pauses, fmtDuration(pause.toLong())))
    return joinList(parts)
}

// ---------------------------------------------------------------------------------------------
// Saving
// ---------------------------------------------------------------------------------------------

fun chainsToJson(list: List<Chain>): String {
    val arr = JSONArray()
    for (c in list) {
        val steps = JSONArray()
        for (s in c.steps) steps.put(JSONObject().put("payload", s.payload).put("wait", s.waitSeconds))
        arr.put(JSONObject().put("id", c.id).put("name", c.name).put("steps", steps))
    }
    return arr.toString()
}

/**
 * Reads saved chains. Whatever is unreadable is left out: a chain with no id, no name or no payload, a second chain with
 * an id that was seen already. Names, pauses and the numbers of payloads and chains are cut to their limits. Broken or
 * missing input gives an empty list.
 */
fun parseChains(json: String?): List<Chain> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        val seen = HashSet<String>()
        val out = ArrayList<Chain>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val name = o.optString("name", "").trim().take(CHAIN_NAME_MAX).trim()
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue
            val stepArray = o.optJSONArray("steps") ?: continue
            val steps = ArrayList<ChainStep>()
            for (k in 0 until stepArray.length()) {
                val s = stepArray.optJSONObject(k) ?: continue
                val payload = s.optString("payload", "")
                if (payload.isEmpty()) continue
                steps.add(ChainStep(payload, s.optInt("wait", 0).coerceIn(0, CHAIN_MAX_WAIT)))
            }
            if (steps.isEmpty()) continue
            out.add(Chain(id, name, steps.take(CHAIN_MAX_STEPS)))
            if (out.size >= CHAINS_MAX) break
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * [incoming] chains put among the [existing] ones: a chain with the same id is replaced where it stands, new ones are
 * added at the end. Nothing already on the phone is lost; the list stays within [CHAINS_MAX].
 */
fun mergeChains(existing: List<Chain>, incoming: List<Chain>): List<Chain> {
    val byId = incoming.associateBy { it.id }
    val merged = existing.map { byId[it.id] ?: it }.toMutableList()
    for (c in incoming) if (merged.none { it.id == c.id }) merged.add(c)
    return merged.take(CHAINS_MAX)
}

/** Saves and loads the chains in the app's settings. */
/**
 * A chain that runs by itself when a console becomes ready: when its loader starts answering after it didn't (a
 * jailbreak just ran), while Bento is on screen. One chain per console; at most once in [COOLDOWN_MS].
 */
object AutoChain {
    private const val COOLDOWN_MS = 10L * 60 * 1000
    private val lastRun = HashMap<String, Long>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The chain set for [profileId], or "" for none. */
    fun get(ctx: Context, profileId: String): String = prefs(ctx).getString("auto_chain_$profileId", "") ?: ""

    fun set(ctx: Context, profileId: String, chainId: String) {
        prefs(ctx).edit().putString("auto_chain_$profileId", chainId).apply()
    }

    /** Whether the loader coming up ([wasUp] false, [nowUp] true) should start the chain now. Notes the run. */
    fun due(profileId: String, wasUp: Boolean?, nowUp: Boolean?, now: Long = System.currentTimeMillis()): Boolean {
        if (wasUp != false || nowUp != true) return false
        val last = lastRun[profileId] ?: 0L
        if (now - last < COOLDOWN_MS) return false
        lastRun[profileId] = now
        return true
    }
}

object ChainStore {
    private const val KEY = "chains_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Chain> = parseChains(prefs(ctx).getString(KEY, null))

    fun save(ctx: Context, list: List<Chain>) {
        prefs(ctx).edit().putString(KEY, chainsToJson(list)).apply()
    }
}

// ---------------------------------------------------------------------------------------------
// Running a chain
// ---------------------------------------------------------------------------------------------

/**
 * How a run of a chain went: [sent] of [total] payloads went out; if one could not be sent, [failedStep] (counted from 1),
 * its file name and [failure], the message that says why; [stopped] when the person stopped the chain.
 */
class ChainOutcome(
    val total: Int,
    val sent: Int,
    val failedStep: Int = 0,
    val failedPayload: String = "",
    val failure: String = "",
    val stopped: Boolean = false
) {
    /** True when every payload went out. */
    val ok: Boolean get() = !stopped && failedStep == 0 && sent == total
}

/** True when [text] is a problem that means nothing reached the console: nothing listens on that port (yet), or no connection could be made. */
fun nothingWasSent(text: String): Boolean {
    val topic = Hints.topicOf(text)
    return topic == HelpTopic.PORT_CLOSED || topic == HelpTopic.UNREACHABLE
}

/**
 * Sends the payloads of [steps] in order with [send], waiting after each as its step says ([sleepSecond] waits one second).
 * A payload that can't be sent ends the chain. After the first payload, a console whose loader doesn't answer yet is
 * tried again for [retrySeconds] (the payload before may still be starting it); that only happens when nothing could have
 * reached the console, so no payload goes out twice. [cancelled] is asked before each payload and each second of
 * waiting. [onStep] gets the number of the payload that is about to go, [onWait] the seconds of the pause that are left,
 * and [onRetry] is told whenever the loader is waited for. [clock] is the time in milliseconds.
 */
suspend fun runChain(
    steps: List<ChainStep>,
    send: suspend (String) -> PayloadReply,
    sleepSecond: suspend () -> Unit,
    cancelled: () -> Boolean = { false },
    onStep: (number: Int, total: Int, payload: String) -> Unit = { _, _, _ -> },
    onWait: (secondsLeft: Int) -> Unit = {},
    onRetry: () -> Unit = {},
    retrySeconds: Int = CHAIN_CONNECT_RETRY_SECONDS,
    clock: () -> Long = { System.nanoTime() / 1_000_000L }
): ChainOutcome {
    val total = steps.size
    var sent = 0
    for ((index, step) in steps.withIndex()) {
        if (cancelled()) return ChainOutcome(total, sent, stopped = true)
        onStep(index + 1, total, step.payload)
        var reply = send(step.payload)
        if (index > 0) {
            val giveUpAt = clock() + retrySeconds * 1000L
            while (!reply.ok && nothingWasSent(reply.text) && clock() < giveUpAt) {
                if (cancelled()) return ChainOutcome(total, sent, stopped = true)
                onRetry()
                sleepSecond()
                reply = send(step.payload)
            }
        }
        if (!reply.ok) return ChainOutcome(total, sent, index + 1, step.payload, reply.text)
        sent++
        if (index < total - 1) {
            var left = step.waitSeconds
            while (left > 0) {
                if (cancelled()) return ChainOutcome(total, sent, stopped = true)
                onWait(left)
                sleepSecond()
                left--
            }
        }
    }
    return ChainOutcome(total, sent)
}

/** The sentence that says how a run went. A payload that could not be sent is followed by the message that says why. */
fun chainResultText(o: ChainOutcome): String {
    val head = when {
        o.ok -> AppText.get(R.string.chain_result_done, o.sent, o.total)
        o.stopped -> AppText.get(R.string.chain_result_stopped, o.sent, o.total)
        else -> AppText.get(R.string.chain_result_failed, o.failedStep, o.failedPayload, o.sent, o.total)
    }
    return if (o.failure.isEmpty()) head else head + "\n" + o.failure
}

// ---------------------------------------------------------------------------------------------
// What the screen and the notification show
// ---------------------------------------------------------------------------------------------

/**
 * What the chain card shows. While [active] the chain is running: it sends payload number [step] of [steps], or waits
 * ([waitLeft] seconds are left of a pause; -1 when it is not waiting), or waits for the loader to come up ([retrying]).
 * Afterwards [message] says how it went and [ok] whether every payload went out. Nothing is shown while [name] is empty.
 */
data class ChainState(
    val active: Boolean = false,
    val name: String = "",
    val console: String = "",
    val step: Int = 0,
    val steps: Int = 0,
    val payload: String = "",
    val waitLeft: Int = -1,
    val retrying: Boolean = false,
    val message: String = "",
    val ok: Boolean = false
)

/** The line that says what a running chain is doing now. */
fun chainProgressText(s: ChainState): String = when {
    s.retrying -> AppText.get(R.string.chain_retrying)
    s.waitLeft >= 0 -> AppText.get(R.string.chain_waiting, fmtDuration(s.waitLeft.toLong()))
    s.step > 0 -> AppText.get(R.string.chain_sending, s.payload, s.step, s.steps)
    else -> AppText.get(R.string.transfer_starting)
}

/** A chain that is ready to run: the payload file names and where to send them. */
class ChainRequest(val name: String, val steps: List<ChainStep>, val host: String, val port: Int, val console: String)

/**
 * Holds what the chain service works on and what the screen shows. One chain runs at a time. The app uses the single
 * instance [ChainRunner]; the tests make their own.
 */
class ChainCenter {
    val state = MutableStateFlow(ChainState())

    /** True once the person asked to stop the running chain. */
    @Volatile
    var stopRequested = false
        private set

    private var pending: ChainRequest? = null

    /**
     * The person asked to run [chain] against the loader at [host]:[port] of the console called [console]; [shelf] are the
     * names of the payloads on the shelf. When the chain can run, the request waits for the service ([takeRequest]) and
     * this returns true: the service has to be started. When it can't, the card says why and this returns false. A second
     * chain is not started while one runs.
     */
    fun request(chain: Chain, host: String, port: String, console: String, shelf: Set<String>): Boolean {
        synchronized(this) {
            if (state.value.active || pending != null) return false
            val portNumber = port.trim().toIntOrNull()
            val missing = missingPayloads(chain, shelf)
            val problem = when {
                host.isBlank() || portNumber == null || portNumber !in 1..65535 -> AppText.get(R.string.chain_err_target)
                missing.isNotEmpty() -> AppText.get(R.string.chain_err_missing, joinList(missing))
                else -> null
            }
            if (problem != null) {
                state.value = ChainState(name = chain.name, console = console, steps = chain.steps.size, message = problem)
                return false
            }
            stopRequested = false
            pending = ChainRequest(chain.name, chain.steps, host.trim(), portNumber!!, console)
            state.value = ChainState(active = true, name = chain.name, console = console, steps = chain.steps.size)
            return true
        }
    }

    /** A chain the person asked for does not exist (for example a shortcut of a chain that was deleted). */
    fun rejected(message: String) {
        synchronized(this) {
            if (state.value.active || pending != null) return
            state.value = ChainState(name = AppText.get(R.string.nav_payload), message = message)
        }
    }

    /** Service: the request to work on, once. Null when there is none. */
    fun takeRequest(): ChainRequest? = synchronized(this) {
        val r = pending
        pending = null
        r
    }

    /** The person stopped the chain: it ends before the next payload, or at the next second of a pause. */
    fun requestStop() {
        stopRequested = true
    }

    fun onStep(number: Int, total: Int, payload: String) {
        state.update { it.copy(step = number, steps = total, payload = payload, waitLeft = -1, retrying = false) }
    }

    fun onWait(secondsLeft: Int) {
        state.update { it.copy(waitLeft = secondsLeft, retrying = false) }
    }

    fun onRetry() {
        state.update { it.copy(retrying = true) }
    }

    /** Service: the chain is over. */
    fun finish(outcome: ChainOutcome) {
        state.update {
            ChainState(name = it.name, console = it.console, steps = outcome.total, message = chainResultText(outcome), ok = outcome.ok)
        }
    }

    /** The service could not be started (or went away): the request is dropped and the card says so. */
    fun failedToStart(message: String) {
        synchronized(this) { pending = null }
        state.update { ChainState(name = it.name, console = it.console, steps = it.steps, message = message) }
    }

    /** Hides the result of a chain that ended. */
    fun dismiss() {
        if (!state.value.active) state.value = ChainState()
    }
}

/** The one chain center of the app, shared by the screen and the chain service. */
val ChainRunner = ChainCenter()
