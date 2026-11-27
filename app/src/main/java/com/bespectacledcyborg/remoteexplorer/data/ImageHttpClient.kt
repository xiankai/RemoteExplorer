package com.bespectacledcyborg.remoteexplorer.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val IMAGE_REQUEST_SLOTS = 24
internal const val RENDER_WAIT_SECONDS = 60L

private val imageRequestThreadCount = AtomicInteger()

private val imageRequestThreads = Executors.newFixedThreadPool(IMAGE_REQUEST_SLOTS) { task ->
    Thread(task, "image-request-${imageRequestThreadCount.incrementAndGet()}").apply {
        isDaemon = true
    }
}.asCoroutineDispatcher()

private fun imageOkHttpClient(): OkHttpClient {
    val dispatcher = Dispatcher().apply {
        maxRequests = IMAGE_REQUEST_SLOTS
        maxRequestsPerHost = IMAGE_REQUEST_SLOTS
    }
    return OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .readTimeout(RENDER_WAIT_SECONDS, TimeUnit.SECONDS)
        .build()
}

internal fun imageHttpClient() = HttpClient(OkHttp) {
    engine {
        dispatcher = imageRequestThreads
        preconfigured = imageOkHttpClient()
    }
}
