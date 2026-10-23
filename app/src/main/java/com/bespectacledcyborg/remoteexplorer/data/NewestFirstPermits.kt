package com.bespectacledcyborg.remoteexplorer.data

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class NewestFirstPermits(permits: Int) {
    private val lock = Any()
    private var available = permits
    private val waiting = ArrayDeque<CancellableContinuation<Unit>>()

    val queued: Int get() = synchronized(lock) { waiting.size }

    val isExhausted: Boolean get() = synchronized(lock) { available == 0 }

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
                waiter.invokeOnCancellation { synchronized(lock) { waiting.remove(waiter) } }  // stack-reviewer-ignore: no permit is lost. This handler only drops a still-queued waiter, which holds no permit. A waiter that release() has already popped and handed the permit to is covered by resume's onCancellation, which kotlinx runs when the continuation was already cancelled or is cancelled before it is dispatched, and which calls release() to pass the permit on. NewestFirstPermitsTest pins exactly that race, and fails if the release() in onCancellation is removed.
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
