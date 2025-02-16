package com.example.remoteexplorer.data

import android.util.Log
import com.hierynomus.smbj.share.File
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
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
                        val totalLength = file.fileInformation.standardInformation.endOfFile

                        val rangeHeader = call.request.headers[HttpHeaders.Range]
                        val range = parseRangeHeaders(rangeHeader, totalLength)
                        commonHeaders(file, range.first, range.last)
                    }
                    get("/{path...}") {
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        Log.d("ServerRepository", "Received GET request for $fullPath")
                        val file = sambaFileDataSource.openFile(fullPath ?: "")
                        val totalLength = file.fileInformation.standardInformation.endOfFile

                        val rangeHeader = call.request.headers[HttpHeaders.Range]
                        val range = parseRangeHeaders(rangeHeader, totalLength)
                        commonHeaders(file, range.first, range.last)

                        if (rangeHeader != null) {
                            Log.d("ServerRepository", "Received range request: $rangeHeader")
                            try {
                                val requestedLength = range.last - range.first + 1
                                val byteArray = ByteArray(requestedLength.toInt())
                                file.read(byteArray, range.first, 0, range.last.toInt())
                                call.response.status(HttpStatusCode.PartialContent)
                                call.respondBytes(byteArray)
                            } catch (e: Exception) {
                                Log.e("ServerRepository", "Error processing range header", e)
                                call.response.status(HttpStatusCode.BadRequest)
                                call.respondText("Error processing requested range")
                            }
                        } else {
                            call.respondOutputStream {
                                file.inputStream.transferTo(this)
                            }
                        }

                        file.close()
                    }
                }
            }.start(wait = true)

        }
    }

    fun constructUrl(path: String): String {
        return "http://127.0.0.1:${port}/${path}"
    }

    private fun parseRangeHeaders(rangeHeader: String?, totalLength: Long): LongRange {
        if (rangeHeader != null) {
            val range = rangeHeader.removePrefix("bytes=")
            val (start, end) = range.split("-").map { it.toLongOrNull() }
            return coerceRange(start, end, totalLength)
        } else {
            return coerceRange(null, null, totalLength)
        }
    }

    private fun commonHeaders(file: File, start: Long, end: Long): Headers {
        val mimeType = URLConnection.guessContentTypeFromName(file.uncPath)

        val requestedLength = end - start + 1
        return headersOf(
            HttpHeaders.AcceptRanges to listOf("bytes"),
            HttpHeaders.ContentType to listOf(mimeType),
            HttpHeaders.ContentLength to listOf(requestedLength.toString()),
            HttpHeaders.ContentRange to listOf("bytes $start-$end/$requestedLength"),
        )
    }

    private fun coerceRange(start: Long?, end: Long?, totalLength: Long): LongRange {
        val actualEnd = end?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
        val actualStart = start?.coerceAtLeast(0)?.coerceAtMost(actualEnd) ?: 0

        return LongRange(actualStart, actualEnd)
    }
}