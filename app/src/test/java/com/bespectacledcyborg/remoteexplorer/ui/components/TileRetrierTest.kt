package com.bespectacledcyborg.remoteexplorer.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class TileRetrierTest {
    private val scope = TestScope()
    private var restarts = 0
    private val retrier = TileRetrier(scope) { restarts++ }

    private fun elapse(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    @Test
    fun `each failure restarts once, after its own delay`() {
        retrier.failed(SocketTimeoutException())
        elapse(999)
        assertEquals(0, restarts)
        elapse(1)
        assertEquals(1, restarts)

        retrier.failed(SocketTimeoutException())
        elapse(2_000)
        assertEquals(2, restarts)
    }

    @Test
    fun `failures that arrive back to back restart once and still use up the retries`() {
        repeat(3) { retrier.failed(SocketTimeoutException()) }
        elapse(60_000)
        assertEquals(1, restarts)

        retrier.failed(SocketTimeoutException())
        elapse(60_000)
        assertEquals(1, restarts)
    }

    @Test
    fun `a server error status is never retried`() {
        retrier.failed(IOException("HTTP code error. 404. Not Found"))
        elapse(60_000)
        assertEquals(0, restarts)
    }

    @Test
    fun `only a 404 from the server reads as a missing thumbnail`() {
        assertEquals(true, TileRetry.isNotFound(IOException("HTTP code error. 404 Not Found. http://127.0.0.1/a.jpg")))
        assertEquals(false, TileRetry.isNotFound(IOException("HTTP code error. 403 Forbidden. http://127.0.0.1/a.jpg")))
        assertEquals(false, TileRetry.isNotFound(SocketTimeoutException("timeout")))
        assertEquals(false, TileRetry.isNotFound(null))
    }

    @Test
    fun `a cancelled retrier does not restart`() {
        retrier.failed(SocketTimeoutException())
        retrier.cancel()
        elapse(60_000)
        assertEquals(0, restarts)
    }
}
