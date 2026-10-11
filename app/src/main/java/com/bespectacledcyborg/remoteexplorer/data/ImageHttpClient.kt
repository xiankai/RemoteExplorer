package com.bespectacledcyborg.remoteexplorer.data

import io.ktor.client.HttpClient
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal const val IMAGE_REQUEST_SLOTS = 24

private val imageRequestThreadCount = AtomicInteger()

private val imageRequestThreads = Executors.newFixedThreadPool(IMAGE_REQUEST_SLOTS) { task ->
    Thread(task, "image-request-${imageRequestThreadCount.incrementAndGet()}").apply {
        isDaemon = true
    }
}.asCoroutineDispatcher()

internal fun imageHttpClient() = HttpClient {
    engine {
        dispatcher = imageRequestThreads
    }
}
