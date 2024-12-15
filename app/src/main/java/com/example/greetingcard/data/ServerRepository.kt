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
                    get("/{path}") {
                        println("Received GET request for ${call.parameters["path"]}")
                        val file = sambaFileDataSource.openFile(call.parameters["path"] ?: "")
                        println("Opened file: ${file}")
                        call.respondOutputStream {
                            file.inputStream.transferTo(this)
                        }
                    }
                }
            }.start(wait = true)

        }
    }

    suspend fun constructUrl(path: String): String {
        return withContext(dispatcher) {
            "http://127.0.0.1:${port}/${path}"
        }
    }
}