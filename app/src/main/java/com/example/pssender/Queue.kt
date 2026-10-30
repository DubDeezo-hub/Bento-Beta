package com.example.pssender

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One transfer in the queue. The [id] is unique for as long as the app runs. */
class QueueEntry<T>(val id: Long, val item: T)

/**
 * The transfers that wait for their turn, in order, plus the one that failed and waits for the user to decide.
 *
 * One worker (the transfer service) works through the queue. The queue knows whether a worker is busy, so
 * [add], [retryParked] and [skipParked] can say whether the caller has to start one. Everything is safe to call
 * from any thread. A plain class with no Android parts, so it can be tested on its own.
 */
class TransferQueue<T> {
    private val lock = Any()
    private var nextId = 1L
    private val waitingList = ArrayList<QueueEntry<T>>()
    private var parkedEntry: QueueEntry<T>? = null
    private var workerBusy = false
    private val waitingFlow = MutableStateFlow<List<QueueEntry<T>>>(emptyList())

    /** The transfers that wait for their turn, first in line first. */
    val waiting: StateFlow<List<QueueEntry<T>>> get() = waitingFlow

    /** How many transfers wait for their turn. */
    val waitingCount: Int get() = waitingFlow.value.size

    private fun publish() {
        waitingFlow.value = ArrayList(waitingList)
    }

    /**
     * Puts [item] at the end of the queue. Returns true when the caller has to start the worker, which is when none
     * is busy and no failed transfer is waiting for a decision (the queue pauses until the user decides).
     */
    fun add(item: T): Boolean {
        synchronized(lock) {
            waitingList.add(QueueEntry(nextId++, item))
            publish()
            if (workerBusy || parkedEntry != null) return false
            workerBusy = true
            return true
        }
    }

    /**
     * Worker: the next transfer to run, or null when nothing is left. After a null the worker counts as stopped,
     * and the next [add] asks for a new one.
     */
    fun takeNext(): QueueEntry<T>? {
        synchronized(lock) {
            if (waitingList.isEmpty()) {
                workerBusy = false
                return null
            }
            val entry = waitingList.removeAt(0)
            publish()
            return entry
        }
    }

    /** Worker: [entry] failed and waits for the user's decision. The worker stops after this call. */
    fun park(entry: QueueEntry<T>) {
        synchronized(lock) {
            parkedEntry = entry
            workerBusy = false
        }
    }

    /** The failed transfer that waits for the user's decision, if there is one. */
    val parked: QueueEntry<T>? get() = synchronized(lock) { parkedEntry }

    /** The failed transfer is tried again, before everything else. Returns true when the caller has to start the worker. */
    fun retryParked(): Boolean {
        synchronized(lock) {
            val entry = parkedEntry ?: return false
            parkedEntry = null
            waitingList.add(0, entry)
            publish()
            if (workerBusy) return false
            workerBusy = true
            return true
        }
    }

    /** The failed transfer is dropped. Returns true when the caller has to start the worker (more transfers wait). */
    fun skipParked(): Boolean {
        synchronized(lock) {
            if (parkedEntry == null) return false
            parkedEntry = null
            if (workerBusy || waitingList.isEmpty()) return false
            workerBusy = true
            return true
        }
    }

    /** Removes one waiting transfer. Returns false when it is not there any more (it may have started). */
    fun remove(id: Long): Boolean {
        synchronized(lock) {
            val removed = waitingList.removeAll { it.id == id }
            if (removed) publish()
            return removed
        }
    }

    /**
     * Drops every waiting transfer and the failed one. The transfer that is running is not touched. Returns what was
     * dropped, so the caller can clean up after exactly those (the worker may take an entry at any moment).
     */
    fun clear(): List<QueueEntry<T>> {
        synchronized(lock) {
            val dropped = ArrayList(waitingList)
            parkedEntry?.let { dropped.add(it) }
            waitingList.clear()
            parkedEntry = null
            publish()
            return dropped
        }
    }

    /** The worker could not be started after all: the next [add] has to ask again. */
    fun workerFailedToStart() {
        synchronized(lock) { workerBusy = false }
    }

    /** True while the worker is busy (running a transfer, or about to). */
    val isWorking: Boolean get() = synchronized(lock) { workerBusy }
}
