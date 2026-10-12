package com.bespectacledcyborg.remoteexplorer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageHttpClientTest {
    @Test
    fun `every slot can reach the loopback server at once`() {
        val dispatcher = imageRequestDispatcher()
        assertEquals(IMAGE_REQUEST_SLOTS, dispatcher.maxRequests)
        assertEquals(IMAGE_REQUEST_SLOTS, dispatcher.maxRequestsPerHost)
    }
}
