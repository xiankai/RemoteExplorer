package com.bespectacledcyborg.remoteexplorer.data

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers

internal const val IMAGE_REQUEST_SLOTS = 8

internal fun imageHttpClient() = HttpClient {
    engine {
        dispatcher = Dispatchers.IO.limitedParallelism(IMAGE_REQUEST_SLOTS)
    }
}
