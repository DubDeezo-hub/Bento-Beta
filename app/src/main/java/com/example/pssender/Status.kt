package com.example.pssender // change to match your project's package name

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// ---------------------------------------------------------------------------------------------
// Live console status: which of the console's services answer right now
// ---------------------------------------------------------------------------------------------

/** What to check: the console's address and the (name, port) of each service. A port that isn't a number is null. */
data class ConsoleTarget(val host: String, val services: List<Pair<String, Int?>>)

/** Whether one service answered. [up] is null while it hasn't been checked yet. */
data class ServiceCheck(val label: String, val port: Int?, val up: Boolean?)

/** The result of checking [target] once. */
data class ConsoleStatus(val target: ConsoleTarget, val checks: List<ServiceCheck>)

/** The overall state shown on the console card. */
enum class ConsoleLevel {
    /** No IP address has been entered yet. */
    UNSET,

    /** The first check hasn't finished. */
    CHECKING,

    /** Every service answers. */
    READY,

    /** Some services answer and some don't. */
    PARTIAL,

    /** Nothing answers. */
    OFFLINE
}

/** Connects to every service of [target] at the same time and reports which ones accepted the connection. */
suspend fun checkConsole(target: ConsoleTarget, timeoutMs: Int = 1500): ConsoleStatus = coroutineScope {
    val checks = target.services.map { (label, port) ->
        async { ServiceCheck(label, port, port != null && isPortOpen(target.host, port, timeoutMs)) }
    }.awaitAll()
    ConsoleStatus(target, checks)
}

/** The checks to show before the first result is in: every service, not known yet. */
fun pendingChecks(target: ConsoleTarget): List<ServiceCheck> =
    target.services.map { (label, port) -> ServiceCheck(label, port, null) }

/**
 * The overall state for [target]. A result that was measured for a different address or ports counts as
 * "not checked yet", so old results never describe the wrong console.
 */
fun consoleLevel(target: ConsoleTarget, status: ConsoleStatus?): ConsoleLevel {
    if (target.host.isBlank()) return ConsoleLevel.UNSET
    if (status == null || status.target != target) return ConsoleLevel.CHECKING
    val used = status.checks.filter { it.port != null }
    val up = used.count { it.up == true }
    return when {
        used.isEmpty() || up == 0 -> ConsoleLevel.OFFLINE
        up == used.size -> ConsoleLevel.READY
        else -> ConsoleLevel.PARTIAL
    }
}

/** Names of the services that were checked and did not answer. */
fun downServices(status: ConsoleStatus?): List<String> =
    status?.checks?.filter { it.port != null && it.up == false }?.map { it.label } ?: emptyList()

/** The short title of the console card. */
fun consoleHeadline(level: ConsoleLevel, down: List<String>, compact: Boolean): String = when (level) {
    ConsoleLevel.UNSET -> AppText.get(R.string.status_set_ip)
    ConsoleLevel.CHECKING -> AppText.get(R.string.status_checking)
    ConsoleLevel.READY -> AppText.get(R.string.status_ready)
    ConsoleLevel.PARTIAL ->
        if (compact && down.isNotEmpty()) AppText.get(R.string.status_partly_off, joinList(down)) else AppText.get(R.string.status_partly)
    ConsoleLevel.OFFLINE -> AppText.get(R.string.status_offline)
}

/** The advice under the card's title, or null when there is nothing to add. */
fun consoleHint(level: ConsoleLevel, down: List<String>): String? = when (level) {
    ConsoleLevel.UNSET -> AppText.get(R.string.status_hint_unset)
    ConsoleLevel.OFFLINE -> AppText.get(R.string.status_hint_offline)
    ConsoleLevel.PARTIAL -> when {
        down.isEmpty() -> null
        down.size == 1 -> AppText.get(R.string.status_hint_one, down[0])
        else -> AppText.get(R.string.status_hint_many, joinList(down))
    }
    else -> null
}
