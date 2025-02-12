package com.example.remoteexplorer.data

import android.util.Log
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.response.contentRange
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
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
            embeddedServer(Jetty, port, host="0.0.0.0") {
                routing {
                    head("/{path...}") {
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        Log.d("ServerRepository", "Received HEAD request for $fullPath")
                        val file = sambaFileDataSource.openFile(fullPath ?: "")
                        val totalLength = file.fileInformation.standardInformation.allocationSize
                        val mimeType = URLConnection.guessContentTypeFromName(file.uncPath)
                        call.response.headers.append(HttpHeaders.ContentType, mimeType)
                        call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                        call.response.headers.append(HttpHeaders.ContentLength, totalLength.toString())
                    }
                    get("/{path...}") {
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        Log.d("ServerRepository", "Received GET request for $fullPath")
                        val file = sambaFileDataSource.openFile(fullPath ?: "")
                        val totalLength = file.fileInformation.standardInformation.allocationSize
                        val mimeType = URLConnection.guessContentTypeFromName(file.uncPath)
                        call.response.headers.append(HttpHeaders.ContentType, mimeType)
                        call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                        call.response.headers.append(HttpHeaders.ContentLength, totalLength.toString())

                        val rangeHeader = call.request.headers[HttpHeaders.Range]
                        if (rangeHeader != null) {
                            Log.d("ServerRepository", "Received range header: $rangeHeader")
                            try {
                                val range = rangeHeader.removePrefix("bytes=")
                                val (start, end) = range.split("-").map { it.toLongOrNull() }

                                val actualEnd = end?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
                                val actualStart = start?.coerceAtLeast(0)?.coerceAtMost(actualEnd) ?: 0
                                val requestedLength = actualEnd - actualStart + 1

                                val byteArray = ByteArray(requestedLength.toInt())
                                file.read(byteArray, actualStart, 0, actualEnd.toInt())

                                call.response.contentRange(LongRange(actualStart, actualEnd), totalLength)
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

    fun constructUrl(path: String): String {
        return "http://127.0.0.1:${port}/${path}"
    }
}