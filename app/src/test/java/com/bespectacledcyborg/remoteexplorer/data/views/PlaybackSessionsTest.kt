package com.bespectacledcyborg.remoteexplorer.data.views

import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionsTest {

    @Test
    fun `bytes served twice count once`() {
        val s = PlaybackSession(startedAt = 0, totalLength = 1000)
        s.add(0, 499, 1)
        s.add(200, 299, 2) // a rewind inside what was already served
        s.add(500, 599, 3) // touching the end
        s.add(900, 999, 4) // a skip forward leaves 600..899 unserved
        assertEquals(700, s.coveredBytes())
    }

    @Test
    fun `seconds are the byte share of the duration, clamped by the wall clock`() {
        val s = PlaybackSession(startedAt = 0, totalLength = 1000)
        s.add(0, 499, 600_000) // half a 20-minute file, over ten minutes
        assertEquals(600.0, s.watchedSeconds(durationMillis = 1_200_000), 1e-9)

        // The whole of a short clip pulled in two seconds is two seconds, not the clip.
        val fast = PlaybackSession(startedAt = 0, totalLength = 1000)
        fast.add(0, 999, 2_000)
        assertEquals(2.0, fast.watchedSeconds(durationMillis = 60_000), 1e-9)
    }

    @Test
    fun `without a duration, the wall-clock span is what is left`() {
        val s = PlaybackSession(startedAt = 0, totalLength = 1000)
        s.add(0, 10, 45_000)
        assertEquals(45.0, s.watchedSeconds(durationMillis = null), 1e-9)
    }

    @Test
    fun `a session flushes as it runs, closes when idle, and the next adds again`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var now = 0L
        val credited = mutableListOf<Double>()
        val clip = FileIdentity(1, "Videos/clip.mp4")
        val sessions = PlaybackSessions(
            scope = scope,
            durationOf = { 100_000L }, // 100 s over 1000 bytes
            credit = { _, seconds -> credited += seconds },
            clock = { now },
        )

        now = 0; sessions.onServed(clip, clip, 0, 99, 1000)
        scope.advanceUntilIdle() // the duration read
        now = 40_000; sessions.onServed(clip, clip, 100, 399, 1000)
        sessions.sweep() // periodic: 40 s of wall clock, 40 s of bytes
        assertEquals(listOf(40.0), credited)

        // The same span again in the same session adds nothing.
        now = 50_000; sessions.onServed(clip, clip, 0, 399, 1000)
        sessions.sweep()
        assertEquals(listOf(40.0), credited)

        // Idle past the gap closes it, and a later viewing of the same span counts again.
        now = 50_000 + PLAYBACK_IDLE_GAP_MILLIS + 1
        sessions.sweep()
        now += 1; sessions.onServed(clip, clip, 0, 199, 1000)
        now += 30_000; sessions.onServed(clip, clip, 200, 399, 1000)
        sessions.sweep()
        assertEquals(2, credited.size)
        assertEquals(30.0, credited[1], 1e-9)
        assertTrue(credited.all { it > 0 })
    }
}
