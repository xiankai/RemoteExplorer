package com.example.remoteexplorer.data

import android.util.Log
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.URLConnection

class ServerRepository (
    private val sambaFileDataSource: SambaFileDataSource,
    private val dispatcher: CoroutineDispatcher
) {
    private var port = 8080

    suspend fun startup() {
        return withContext(dispatcher) {
            embeddedServer(Jetty, port) {
                routing {
                    get("/{path...}") {
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        Log.d("ServerRepository", "Received GET request for $fullPath")
                        val file = sambaFileDataSource.openFile(fullPath ?: "")
                        val mimeType = URLConnection.guessContentTypeFromName(file.uncPath)
                        call.response.headers.append(HttpHeaders.ContentType, mimeType)
                        call.respondOutputStream {
                            file.inputStream.transferTo(this)
                        }
                    }
                }
            }.start(wait = true)

        }
    }

    fun constructUrl(path: String): String {
        return "http://127.0.0.1:${port}/${path}"
    }
}