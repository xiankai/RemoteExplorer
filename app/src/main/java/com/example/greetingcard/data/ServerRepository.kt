package com.example.greetingcard.data

import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class ServerRepository (
    private val sambaFileDataSource: SambaFileDataSource,
    private val dispatcher: CoroutineDispatcher
) {
    private var port = 8080

    suspend fun startup() {
        return withContext(dispatcher) {
            embeddedServer(Jetty, 8080) {
                routing {
                    get("/{path...}") {
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        println("Received GET request for $fullPath")
                        val file = sambaFileDataSource.openFile(fullPath ?: "")
                        println("Opened file: $file")
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