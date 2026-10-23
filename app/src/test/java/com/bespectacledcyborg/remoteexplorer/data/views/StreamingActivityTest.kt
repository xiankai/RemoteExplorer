package com.bespectacledcyborg.remoteexplorer.data.views

import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingActivityTest {
    private var now = 0L
    private val activity = StreamingActivity { now }
    private val film = FileIdentity(1, "Movies/film.mkv")

    @Test
    fun `a stream stays awake until nothing has been served for the idle gap`() {
        now += STREAMING_IDLE_MILLIS - 1
        assertFalse(activity.isIdle())
        now += 1
        assertTrue(activity.isIdle())
    }

    @Test
    fun `every byte served pushes the idle point back`() {
        now += STREAMING_IDLE_MILLIS - 1
        activity.onServed(film, film, 0, 1023, 10_000)
        now += STREAMING_IDLE_MILLIS - 1
        assertFalse(activity.isIdle())
    }

    @Test
    fun `handing a file to a player counts as activity`() {
        now += STREAMING_IDLE_MILLIS
        activity.touch()
        assertFalse(activity.isIdle())
    }
}
