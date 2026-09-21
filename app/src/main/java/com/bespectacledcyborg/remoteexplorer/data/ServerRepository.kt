package com.bespectacledcyborg.remoteexplorer.data

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailSources
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.bespectacledcyborg.remoteexplorer.utils.Utils.findAvailablePort
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.URLConnection
import java.net.URLDecoder

const val ENCODING = "UTF-8"

class ServerRepository(
    private val sambaFileDataSource: SambaFileDataSource,
    private val dispatcher: CoroutineDispatcher,
    private val thumbnailManager: ThumbnailManager,
    private val thumbnailSources: ThumbnailSources,
) {
    private fun extractConnectionId(call: RoutingCall): Int {
        val connectionId: Int =
            call.queryParameters["connectionId"]?.toInt()
                ?: throw IllegalArgumentException(
                    "Invalid connectionId parameter"
                )

        return connectionId
    }

    suspend fun startup() {
        val port = findAvailablePort()
        // The port is chosen at random each launch, and until now nothing said which one.
        // Every consumer gets it through constructUrl, so the app never needed to know —
        // but anything outside the app does, and there was no way to find out short of
        // reading /proc/net/tcp. tools/bench reads this line.
        Log.i("ServerRepository", "Serving SMB over HTTP on 127.0.0.1:$port")
        return withContext(dispatcher) {
            // Loopback only. This server re-serves the whole SMB share over plain HTTP with
            // no authentication — `connectionId` is a small integer, not a credential — so
            // binding 0.0.0.0 published the share to everyone on the network, and to every
            // other device on a coffee-shop wifi.
            //
            // Nothing is lost by this: the only consumers are in-process (Sketch, via
            // constructUrl, which already builds 127.0.0.1 URLs) and other apps on the SAME
            // device via ACTION_VIEW, which reach loopback fine because loopback is
            // per-device, not per-process.
            embeddedServer(Jetty, port, host = "127.0.0.1") {
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

                        Log.d(
                            "ServerRepository",
                            "Received GET request for $decodedPath (thumbnail: $isThumbnail)"
                        )

                        if (isThumbnail) {
                            // How long this request took, and which of the three ways it
                            // ended. A cache hit and a render differ by orders of magnitude,
                            // so a single average over them says nothing — tools/bench
                            // separates them on this line. It is also the only per-file
                            // timing the app emits at all, which is what made the cost of
                            // this endpoint guesswork before.
                            val startedAt = System.currentTimeMillis()
                            fun logOutcome(outcome: String) = Log.d(
                                "ServerRepository",
                                "Thumbnail $outcome for $decodedPath in " +
                                        "${System.currentTimeMillis() - startedAt}ms"
                            )

                            val thumbnailFile =
                                thumbnailManager.getThumbnailPath(connectionId, decodedPath)

                            if (thumbnailFile.exists()) {
                                call.respondFile(thumbnailFile)
                                logOutcome("cached")
                                return@get
                            }

                            // Which source handles this file is decided by its media type,
                            // so images and video can be served from different places.
                            val source = thumbnailSources.forType(Utils.guessIconType(decodedPath))
                            if (source == null) {
                                Log.d("ServerRepository", "No thumbnail source for $decodedPath")
                                call.response.status(HttpStatusCode.NotFound)
                                call.respondText("No thumbnail available")
                                logOutcome("unsupported")
                                return@get
                            }

                            val rendered = thumbnailManager.renderInto(connectionId, decodedPath) {
                                source.render(connectionId, decodedPath, it)
                            }

                            if (rendered != null) {
                                call.respondFile(rendered)
                                logOutcome("rendered")
                            } else {
                                Log.d("ServerRepository", "Nothing to render for $decodedPath")
                                call.response.status(HttpStatusCode.NotFound)
                                call.respondText("No thumbnail available")
                                logOutcome("failed")
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
}
