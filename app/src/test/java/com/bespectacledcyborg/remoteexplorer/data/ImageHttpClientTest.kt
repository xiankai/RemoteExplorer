package com.bespectacledcyborg.remoteexplorer.data

import io.ktor.client.engine.okhttp.OkHttpConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageHttpClientTest {
    @Test
    fun `every slot can reach the loopback server at once`() {
        val client = imageHttpClient()
        val dispatcher = (client.engine.config as OkHttpConfig).preconfigured!!.dispatcher
        client.close()

        assertEquals(IMAGE_REQUEST_SLOTS, dispatcher.maxRequests)
        assertEquals(IMAGE_REQUEST_SLOTS, dispatcher.maxRequestsPerHost)
    }
}
