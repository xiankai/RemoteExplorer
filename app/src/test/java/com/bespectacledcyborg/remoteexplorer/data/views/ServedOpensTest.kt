package com.bespectacledcyborg.remoteexplorer.data.views

import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ServedOpensTest {

    private val film = FileIdentity(1, "Movies/film.mkv")
    private val clip = FileIdentity(1, "Videos/clip.mp4")

    private fun TestScope.opens(now: () -> Long, recorded: MutableList<FileIdentity>) =
        ServedOpens(
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            record = { recorded += it },
            clock = now,
        )

    @Test
    fun `nothing served, nothing opened`() = runTest {
        val recorded = mutableListOf<FileIdentity>()
        opens({ 0L }, recorded)
        advanceUntilIdle()
        assertEquals(emptyList<FileIdentity>(), recorded)
    }

    @Test
    fun `one viewing is one open however many requests it makes`() = runTest {
        var now = 0L
        val recorded = mutableListOf<FileIdentity>()
        val opens = opens({ now }, recorded)

        for (minute in 0L..120L step 10) {
            now = minute * 60_000
            opens.onServed(film)
        }
        advanceUntilIdle()

        assertEquals(listOf(film), recorded)
    }

    @Test
    fun `coming back after the idle gap is another open`() = runTest {
        var now = 0L
        val recorded = mutableListOf<FileIdentity>()
        val opens = opens({ now }, recorded)

        opens.onServed(film)
        now = OPEN_IDLE_GAP_MILLIS
        opens.onServed(film)
        advanceUntilIdle()

        assertEquals(listOf(film, film), recorded)
    }

    @Test
    fun `each file keeps its own gap`() = runTest {
        val recorded = mutableListOf<FileIdentity>()
        val opens = opens({ 0L }, recorded)

        opens.onServed(film)
        opens.onServed(clip)
        opens.onServed(film)
        advanceUntilIdle()

        assertEquals(listOf(film, clip), recorded)
    }
}
