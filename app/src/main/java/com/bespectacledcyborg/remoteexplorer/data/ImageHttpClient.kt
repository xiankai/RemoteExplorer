package com.bespectacledcyborg.remoteexplorer.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.Dispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal const val IMAGE_REQUEST_SLOTS = 24

private val imageRequestThreadCount = AtomicInteger()

private val imageRequestThreads = Executors.newFixedThreadPool(IMAGE_REQUEST_SLOTS) { task ->
    Thread(task, "image-request-${imageRequestThreadCount.incrementAndGet()}").apply {
        isDaemon = true
    }
}.asCoroutineDispatcher()

internal fun imageRequestDispatcher(): Dispatcher = Dispatcher().apply {
    maxRequests = IMAGE_REQUEST_SLOTS
    maxRequestsPerHost = IMAGE_REQUEST_SLOTS
}

internal fun imageHttpClient() = HttpClient(OkHttp) {
    engine {
        dispatcher = imageRequestThreads
        config { dispatcher(imageRequestDispatcher()) }
    }
}
