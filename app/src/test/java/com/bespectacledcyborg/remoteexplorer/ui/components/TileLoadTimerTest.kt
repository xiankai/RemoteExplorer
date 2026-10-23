package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetric
import com.bespectacledcyborg.remoteexplorer.data.perf.RecordingLoadMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLoadTimerTest {
    private var now = 1_000L
    private val metrics = RecordingLoadMetrics()
    private val timer = TileLoadTimer(metrics, mapOf("client_slots" to "8")) { now }

    @Test
    fun `a tile that loads records the time from start to image and where it came from`() {
        timer.started()
        now += 420
        timer.succeeded("result_cache")

        val recorded = metrics.recorded.single()
        assertEquals(LoadMetric.TILE_LOAD, recorded.metric)
        assertEquals(420L, recorded.millis)
        assertEquals(
            mapOf("client_slots" to "8", "result" to "ok", "from" to "result_cache", "attempt" to "1"),
            recorded.attributes,
        )
    }

    @Test
    fun `a failed tile is recorded as an error with its cause`() {
        timer.started()
        now += 90
        timer.failed(java.net.SocketTimeoutException("timeout"))

        assertEquals(
            mapOf(
                "client_slots" to "8",
                "result" to "error",
                "from" to "none",
                "attempt" to "1",
                "error" to "SocketTimeoutException",
            ),
            metrics.recorded.single().attributes,
        )
    }

    @Test
    fun `a retry after a failure is recorded as the next attempt`() {
        timer.started()
        timer.failed()
        timer.started()
        timer.succeeded("network")

        assertEquals(listOf("1", "2"), metrics.recorded.map { it.attributes["attempt"] })
    }

    @Test
    fun `a tile scrolled away before it loads records nothing`() {
        timer.started()
        timer.canceled()
        timer.succeeded("memory_cache")

        assertTrue(metrics.recorded.isEmpty())
    }

    @Test
    fun `a cache hit that never started loading is recorded as instant`() {
        timer.succeeded("memory_cache")

        val recorded = metrics.recorded.single()
        assertEquals(0L, recorded.millis)
        assertEquals("memory_cache", recorded.attributes["from"])
    }

    @Test
    fun `a tile is recorded once even if Sketch reports success twice`() {
        timer.started()
        now += 50
        timer.succeeded("network")
        timer.succeeded("network")

        assertEquals(1, metrics.recorded.size)
    }

    @Test
    fun `a timed out tile is retried with a growing delay, then left alone`() {
        val timeout = java.net.SocketTimeoutException("timeout")
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, null),
            (0..3).map { TileRetry.delayBefore(it, timeout) },
        )
    }

    @Test
    fun `a tile the server answered with an error status is not retried`() {
        val notFound = java.io.IOException("HTTP code error. 404. Not Found. http://127.0.0.1/a.jpg")
        assertEquals(null, TileRetry.delayBefore(0, notFound))
    }
}
