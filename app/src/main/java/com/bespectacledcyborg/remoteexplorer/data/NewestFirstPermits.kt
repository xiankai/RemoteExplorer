package com.bespectacledcyborg.remoteexplorer.data

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class NewestFirstPermits(permits: Int) {
    private val lock = Any()
    private var available = permits
    private val waiting = ArrayDeque<CancellableContinuation<Unit>>()

    val queued: Int get() = synchronized(lock) { waiting.size }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire() {
        if (tryTake()) return
        suspendCancellableCoroutine { waiter ->
            val granted = synchronized(lock) {
                if (available > 0) {
                    available--
                    true
                } else {
                    waiting.addLast(waiter)
                    false
                }
            }
            if (granted) {
                waiter.resume(Unit) { _, _, _ -> release() }
            } else {
                waiter.invokeOnCancellation { synchronized(lock) { waiting.remove(waiter) } }
            }
        }
    }

    private fun tryTake(): Boolean = synchronized(lock) {
        if (available > 0) {
            available--
            true
        } else {
            false
        }
    }

    private fun release() {
        val next = synchronized(lock) {
            waiting.removeLastOrNull().also { if (it == null) available++ }
        } ?: return
        next.resume(Unit) { _, _, _ -> release() }
    }
}
