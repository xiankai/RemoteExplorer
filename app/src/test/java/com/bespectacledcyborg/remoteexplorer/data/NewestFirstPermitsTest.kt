package com.bespectacledcyborg.remoteexplorer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewestFirstPermitsTest {

    @Test
    fun `the newest waiter gets the next permit`() = runTest {
        val permits = NewestFirstPermits(1)
        val holder = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        launch { permits.withPermit { holder.await() } }
        runCurrent()
        for (name in listOf("scrolled-past", "older", "on-screen")) {
            launch { permits.withPermit { order += name } }
            runCurrent()
        }

        holder.complete(Unit)
        runCurrent()

        assertEquals(listOf("on-screen", "older", "scrolled-past"), order)
    }

    @Test
    fun `no more than the permitted number run at once`() = runTest {
        val permits = NewestFirstPermits(3)
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0

        repeat(10) {
            launch {
                permits.withPermit {
                    running++
                    peak = maxOf(peak, running)
                    gate.await()
                    running--
                }
            }
        }
        runCurrent()
        assertEquals(3, peak)
        assertEquals(7, permits.queued)

        gate.complete(Unit)
        runCurrent()
        assertEquals(0, running)
        assertEquals(0, permits.queued)
    }

    @Test
    fun `a waiter cancelled while queued neither runs nor loses a permit`() = runTest {
        val permits = NewestFirstPermits(1)
        val holder = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()

        launch { permits.withPermit { holder.await() } }
        runCurrent()
        launch { permits.withPermit { ran += "kept" } }
        runCurrent()
        val scrolledAway = launch { permits.withPermit { ran += "cancelled" } }
        runCurrent()

        scrolledAway.cancel()
        runCurrent()
        assertEquals(1, permits.queued)

        holder.complete(Unit)
        runCurrent()
        assertEquals(listOf("kept"), ran)

        var afterwards = false
        launch { permits.withPermit { afterwards = true } }
        runCurrent()
        assertEquals(true, afterwards)
    }

    @Test
    fun `a block that throws still returns its permit`() = runTest {
        val permits = NewestFirstPermits(1)

        runCatching { permits.withPermit { error("render failed") } }

        var ran = false
        permits.withPermit { ran = true }
        assertEquals(true, ran)
    }
}
