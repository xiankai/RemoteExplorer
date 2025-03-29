package com.bespectacledcyborg.remoteexplorer.data

import android.util.Log
import com.hierynomus.smbj.share.File
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

const val ENCODING = "UTF-8"

class ServerRepository(
    private val sambaFileDataSource: SambaFileDataSource,
    private val dispatcher: CoroutineDispatcher,
    private val thumbnailManager: ThumbnailManager,
) {
    private var port = 0

    private fun extractConnectionId(call: RoutingCall): Int {
        val connectionId: Int =
            call.queryParameters["connectionId"]?.toInt()
                ?: throw IllegalArgumentException(
                    "Invalid connectionId parameter"
                )

        return connectionId
    }

    suspend fun startup() {
        port = findAvailablePort()
        return withContext(dispatcher) {
            embeddedServer(Jetty, port, host = "0.0.0.0") {
                routing {
                    options("/{path...}") {
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        Log.d("ServerRepository", "Received OPTIONS request for $fullPath")
                        // Set CORS headers manually
                        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
                        call.response.headers.append(
                            HttpHeaders.AccessControlAllowMethods,
                            "GET, HEAD, OPTIONS"
                        )
//                        call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "Content-Type, Authorization")
                        call.response.headers.append(
                            HttpHeaders.AccessControlMaxAge,
                            "86400"
                        ) // 24 hours in seconds

                        // Respond with 200 OK status
                        call.respond(HttpStatusCode.OK)
                    }
                    head("/{path...}") {
                        val connectionId = extractConnectionId(call)
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        Log.d("ServerRepository", "Received HEAD request for $fullPath")
                        val file =
                            sambaFileDataSource.openFile(connectionId, fullPath ?: "")
                        val totalLength = file.fileInformation.standardInformation.endOfFile

                        val rangeHeader = call.request.headers[HttpHeaders.Range]
                        val range = parseRangeHeaders(rangeHeader, totalLength)
                        val standardHeaders = commonHeaders(file, range.first, range.last)
                        for (name in standardHeaders.names()) {
                            standardHeaders.getAll(name)?.forEach { value ->
                                call.response.headers.append(name, value)
                            }
                        }
                    }
                    get("/{path...}") {
                        val connectionId = extractConnectionId(call)
                        val fullPath = call.parameters.getAll("path")?.joinToString("/")
                        val decodedPath = URLDecoder.decode(fullPath, ENCODING)
                        val isThumbnail = call.queryParameters["thumbnail"] == "true"
                        val parentPath = call.queryParameters["parentPath"] ?: ""

                        Log.d(
                            "ServerRepository",
                            "Received GET request for $decodedPath (thumbnail: $isThumbnail)"
                        )

                        if (isThumbnail) {
                            // Check if thumbnail exists
                            if (!thumbnailManager.hasThumbnail(decodedPath)) {
                                // If not, create thumbnail synchronously to return
                                Log.d("ServerRepository", "Creating thumbnail for $decodedPath")
                                runBlocking {
                                    thumbnailManager.awaitThumbnailGeneration(
                                        connectionId,
                                        parentPath,
                                        decodedPath
                                    )
                                }
                            }
                            // Check again
                            if (thumbnailManager.hasThumbnail(decodedPath)) {
                                // Serve thumbnail
                                val thumbnailFile =
                                    thumbnailManager.getThumbnailPath(decodedPath)
                                call.respondFile(thumbnailFile)
                            } else {
                                Log.d("ServerRepository", "Thumbnail not yet available")
                                // Respond with a placeholder or error
                                call.response.status(HttpStatusCode.NotFound)
                                call.respondText("Thumbnail not yet available")
                            }
                            return@get
                        }

                        // Serve original image
                        val file =
                            sambaFileDataSource.openFile(
                                connectionId,
                                decodedPath ?: ""
                            )
                        file.use {
                            val totalLength =
                                file.fileInformation.standardInformation.endOfFile

                            val rangeHeader = call.request.headers[HttpHeaders.Range]
                            val range = parseRangeHeaders(rangeHeader, totalLength)

                            val standardHeaders = commonHeaders(file, range.first, range.last)
                            for (name in standardHeaders.names()) {
                                standardHeaders.getAll(name)?.forEach { value ->
                                    call.response.headers.append(name, value)
                                }
                            }

                            if (rangeHeader != null) {
                                Log.d(
                                    "ServerRepository",
                                    "Received range request: $rangeHeader"
                                )
                                try {
                                    call.respondOutputStream {
                                        val input = file.inputStream.buffered()
                                        input.skip(range.first)
                                        val buffer = ByteArray(8192)
                                        var bytesRemaining = range.last - range.first + 1

                                        while (bytesRemaining > 0) {
                                            // Let the buffered stream handle efficient reading
                                            val bytesToRead =
                                                minOf(buffer.size.toLong(), bytesRemaining).toInt()
                                            val bytesRead = input.read(buffer, 0, bytesToRead)

                                            if (bytesRead <= 0) break

                                            write(buffer, 0, bytesRead)
                                            bytesRemaining -= bytesRead
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(
                                        "ServerRepository",
                                        "Error processing partial request",
                                        e
                                    )
                                    call.response.status(HttpStatusCode.BadRequest)
                                    call.respondText("Error processing partial request")
                                }
                            } else {
                                call.respondOutputStream {
                                    file.inputStream.transferTo(this)
                                }
                            }
                        }
                    }
                }
            }.start(wait = true)
        }
    }

    fun constructUrl(connectionId: Int, path: String, thumbnail: Boolean = false): String {
        val encodedPath = URLEncoder.encode(path, ENCODING)
        val baseUrl = "http://127.0.0.1:${port}/${encodedPath}?connectionId=${connectionId}"
        return if (thumbnail) "$baseUrl&thumbnail=true" else baseUrl
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
            HttpHeaders.ContentRange to listOf("bytes $start-$end"),
        )
    }

    private fun coerceRange(start: Long?, end: Long?, totalLength: Long): LongRange {
        val actualEnd = end?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
        val actualStart = start?.coerceAtLeast(0)?.coerceAtMost(actualEnd) ?: 0

        return LongRange(actualStart, actualEnd)
    }

    /**
     * Finds an available port by creating a ServerSocket with port 0, which makes the system
     * allocate an available port. The socket is immediately closed after getting the port.
     */
    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
