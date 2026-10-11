package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetric
import com.bespectacledcyborg.remoteexplorer.data.perf.RecordingLoadMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLoadTimerTest {
    private var now = 1_000L
    private val metrics = RecordingLoadMetrics()
    private val timer = TileLoadTimer(metrics) { now }

    @Test
    fun `a tile that loads records the time from start to image and where it came from`() {
        timer.started()
        now += 420
        timer.succeeded("result_cache")

        val recorded = metrics.recorded.single()
        assertEquals(LoadMetric.TILE_LOAD, recorded.metric)
        assertEquals(420L, recorded.millis)
        assertEquals(mapOf("result" to "ok", "from" to "result_cache"), recorded.attributes)
    }

    @Test
    fun `a failed tile is recorded as an error`() {
        timer.started()
        now += 90
        timer.failed()

        assertEquals(mapOf("result" to "error", "from" to "none"), metrics.recorded.single().attributes)
    }

    @Test
    fun `a tile scrolled away before it loads records nothing`() {
        timer.started()
        timer.canceled()
        timer.succeeded("memory_cache")

        assertTrue(metrics.recorded.isEmpty())
    }

    @Test
    fun `a result with no start is not timed from zero`() {
        timer.succeeded("memory_cache")

        assertTrue(metrics.recorded.isEmpty())
    }
}
