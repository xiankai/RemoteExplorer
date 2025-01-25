package com.example.remoteexplorer.data

import android.util.Log
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
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
                install(AutoHeadResponse)
                routing {
                    get("/{path...}") {
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        Log.d("ServerRepository", "Received GET request for $fullPath")
                        val file = sambaFileDataSource.openFile(fullPath ?: "")
                        val totalLength = file.fileInformation.standardInformation.allocationSize
                        val mimeType = URLConnection.guessContentTypeFromName(file.uncPath)
                        call.response.headers.append(HttpHeaders.ContentType, mimeType)


                        val rangeHeader = call.request.headers[HttpHeaders.Range]
                        if (rangeHeader != null) {
                            Log.d("ServerRepository", "Received range header: $rangeHeader")
                            try {
                                // Parse the range (e.g., "bytes=0-1023")
                                val range = rangeHeader.removePrefix("bytes=")
                                val (start, end) = range.split("-").map { it.toLongOrNull() ?: 0L }

                                val actualEnd = if (end == 0L) totalLength - 1 else end.coerceAtMost(totalLength - 1)
                                val byteRangeLength = actualEnd - start + 1

                                // Get the range of bytes to respond with
                                val byteArray = ByteArray(byteRangeLength.toInt())
                                file.inputStream.read(byteArray, start.toInt(), byteRangeLength.toInt())

                                call.response.status(HttpStatusCode.PartialContent)
                                call.respondBytes(byteArray)
                            } catch (e: Exception) {
                                Log.e("ServerRepository", "Error parsing range header: $e")
                                call.response.status(HttpStatusCode.BadRequest)
                                call.respondText("Invalid range")
                            }
                        } else {
                            call.respondOutputStream {
                                file.inputStream.transferTo(this)
                            }
                        }
                    }
                }
            }.start(wait = true)

        }
    }

    suspend fun oldstartup() {
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