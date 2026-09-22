package com.bespectacledcyborg.remoteexplorer.data

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailSources
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailStatusStore
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URLConnection
import java.net.URLDecoder

const val ENCODING = "UTF-8"

/**
 * How much to pull per SMB read while serving a range.
 *
 * The old loop used 8 KB, which was a sensible size for a buffered local stream and a poor
 * one for a round trip to a file server: it put a request on the wire for every 8 KB of a
 * video. 64 KB sits inside the usual SMB2 maximum read size, so it stays one round trip
 * each while needing an eighth as many.
 */
private const val RANGE_CHUNK_BYTES = 64 * 1024

/** See ServerRepository.renderSlots. */
private const val MAX_CONCURRENT_RENDERS = 3

class ServerRepository(
    private val sambaFileDataSource: SambaFileDataSource,
    private val dispatcher: CoroutineDispatcher,
    private val thumbnailManager: ThumbnailManager,
    private val thumbnailSources: ThumbnailSources,
    private val thumbnailStatuses: ThumbnailStatusStore,
) {
    /**
     * How many thumbnails may be produced at once.
     *
     * A grid of forty video cells means forty simultaneous requests, and each one is an SMB
     * read plus a decode. Nothing bounded that, and the benchmark shows it buys almost
     * nothing: four in flight improved wall clock 32s to 28s while roughly doubling every
     * individual request, because the share and the decoder are the contended resources
     * either way. Three keeps the pipeline busy without turning one slow file into forty
     * slow ones.
     */
    private val renderSlots = Semaphore(MAX_CONCURRENT_RENDERS)

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
                        // `use`, because this handler leaked an SMB handle on every call —
                        // the GET below has always closed its own.
                        sambaFileDataSource.openFile(connectionId, fullPath ?: "").use { file ->
                            val totalLength =
                                file.fileInformation.standardInformation.endOfFile

                            val rangeHeader = call.request.headers[HttpHeaders.Range]
                            val range = parseRangeHeaders(rangeHeader, totalLength)
                            if (range == null) {
                                respondUnsatisfiableRange(call, totalLength)
                                return@head
                            }

                            val standardHeaders = commonHeaders(
                                file, range.first, range.last, totalLength, rangeHeader != null
                            )
                            for (name in standardHeaders.names()) {
                                standardHeaders.getAll(name)?.forEach { value ->
                                    call.response.headers.append(name, value)
                                }
                            }
                            if (rangeHeader != null) {
                                call.response.status(HttpStatusCode.PartialContent)
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

                            // `?decoder=` names a specific implementation instead of the
                            // default for this media type. It is how the two video decoders
                            // get measured against each other on one build and one set of
                            // files (#41) — rebuilding between runs makes a before/after
                            // impossible to trust. A diagnostic request bypasses the cache
                            // entirely: both decoders would otherwise share one cache key,
                            // so the second would be handed the first one's output and
                            // timed at zero.
                            val variant = call.queryParameters["decoder"]

                            val thumbnailFile =
                                thumbnailManager.getThumbnailPath(connectionId, decodedPath)

                            if (variant == null && thumbnailFile.exists()) {
                                call.respondFile(thumbnailFile)
                                logOutcome("cached")
                                return@get
                            }

                            // Already known to be unrenderable. Without this the full read
                            // and decode runs again every time the cell scrolls back into
                            // view, for a guaranteed failure — and since the fallback chain
                            // landed that is two decoders, not one. A diagnostic request
                            // ignores the record, because measuring a decoder means asking
                            // it rather than being told what it said last time.
                            if (variant == null) {
                                val known = thumbnailStatuses.statusOf(connectionId, decodedPath)
                                if (known == ThumbnailStatus.ERROR ||
                                    known == ThumbnailStatus.UNSUPPORTED
                                ) {
                                    call.response.status(HttpStatusCode.NotFound)
                                    call.respondText("No thumbnail available")
                                    logOutcome("known-${known.name.lowercase()}")
                                    return@get
                                }
                            }

                            // Which source handles this file is decided by its media type,
                            // so images and video can be served from different places.
                            val source = thumbnailSources.forType(
                                Utils.guessIconType(decodedPath), variant
                            )
                            if (source == null) {
                                Log.d("ServerRepository", "No thumbnail source for $decodedPath")
                                thumbnailStatuses.record(
                                    connectionId, decodedPath, ThumbnailStatus.UNSUPPORTED
                                )
                                call.response.status(HttpStatusCode.NotFound)
                                call.respondText("No thumbnail available")
                                logOutcome("unsupported")
                                return@get
                            }

                            // Bounded, so a folder of videos cannot put one decode per cell
                            // in flight at once. Held across the render only — the cache hit
                            // above and the response below are not the contended part.
                            val rendered = renderSlots.withPermit {
                                if (variant != null) {
                                    thumbnailManager.renderUncached {
                                        source.render(connectionId, decodedPath, it)
                                    }
                                } else {
                                    thumbnailManager.renderInto(connectionId, decodedPath) {
                                        source.render(connectionId, decodedPath, it)
                                    }
                                }
                            }

                            // Record before responding. A client that hangs up mid-transfer
                            // must not cost the decode again next time — the work already
                            // happened and its answer is just as valid.
                            if (variant == null) {
                                thumbnailStatuses.record(
                                    connectionId,
                                    decodedPath,
                                    if (rendered != null) {
                                        ThumbnailStatus.SUCCESS
                                    } else {
                                        ThumbnailStatus.ERROR
                                    },
                                )
                            }

                            if (rendered != null) {
                                try {
                                    call.respondFile(rendered)
                                    logOutcome(
                                        if (variant != null) "rendered:$variant" else "rendered"
                                    )
                                } finally {
                                    // renderUncached hands over a file the caller owns, and
                                    // trimToSize deliberately skips .tmp so an orphan is
                                    // never evicted. respondFile throws if the client hangs
                                    // up mid-transfer, which a timed benchmark request can
                                    // do, so the delete cannot sit after it.
                                    if (variant != null) rendered.delete()
                                }
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
                            if (range == null) {
                                respondUnsatisfiableRange(call, totalLength)
                                return@use
                            }

                            val standardHeaders = commonHeaders(
                                file, range.first, range.last, totalLength, rangeHeader != null
                            )
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
                                // A partial body has to say so. This answered 200 with a
                                // Content-Range attached, which is not a thing: a client
                                // entitled to take 200 at its word would treat the slice as
                                // the whole file.
                                call.response.status(HttpStatusCode.PartialContent)
                                try {
                                    call.respondOutputStream {
                                        // Read AT the offset rather than streaming from zero
                                        // and discarding. The old code opened the file at
                                        // byte 0 and skipped forward, so seeking to the end
                                        // of a 2 GB video pulled 2 GB across the wire to
                                        // throw it away — which is why seeking in an
                                        // external player crawled.
                                        //
                                        // It was also wrong, not just slow: InputStream.skip
                                        // may skip fewer bytes than asked and the result was
                                        // ignored, so a short skip silently served the bytes
                                        // AFTER the wrong offset. smbj takes the offset
                                        // directly, which removes the question.
                                        val buffer = ByteArray(RANGE_CHUNK_BYTES)
                                        var offset = range.first
                                        var remaining = range.last - range.first + 1

                                        while (remaining > 0) {
                                            val want =
                                                minOf(buffer.size.toLong(), remaining).toInt()
                                            val read = file.read(buffer, offset, 0, want)  // stack-reviewer-ignore: argument order confirmed against smbj-0.14.0 in the Gradle cache — `javap` gives `public int read(byte[], long, int, int)`, i.e. (buffer, fileOffset, bufferOffset, length). The long in position two also means the alternative order would not compile, since `offset` is a Long. Verified on device as well: returned bytes compared byte-for-byte against the source file at offsets 0, 1000000, total-500 and total-1, all matching, and a suffix range matches the tail rather than the head. LocalImageThumbnailSource.readHeader already calls it the same way.
                                            if (read <= 0) break

                                            write(buffer, 0, read)
                                            offset += read
                                            remaining -= read
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

    /** 416, with the `bytes * /total` the spec asks for so the client can retry sensibly. */
    private suspend fun respondUnsatisfiableRange(call: RoutingCall, totalLength: Long) {
        call.response.headers.append(HttpHeaders.ContentRange, "bytes */$totalLength")
        call.response.status(HttpStatusCode.RequestedRangeNotSatisfiable)
        call.respondText("Requested range not satisfiable")
    }

    private fun commonHeaders(
        file: File,
        start: Long,
        end: Long,
        totalLength: Long,
        isPartial: Boolean,
    ): Headers {
        val mimeType = URLConnection.guessContentTypeFromName(file.uncPath)

        val requestedLength = end - start + 1
        val headers = mutableListOf(
            HttpHeaders.AcceptRanges to listOf("bytes"),
            HttpHeaders.ContentType to listOf(mimeType),
            HttpHeaders.ContentLength to listOf(requestedLength.toString()),
        )
        // Content-Range belongs on a 206 and nowhere else, and it has to carry the total —
        // "bytes 0-99" without "/12345" is malformed, and a player that parses it strictly
        // cannot work out how long the file is or how far it may seek. Both were wrong
        // here: the total was missing, and the header went out on full responses too.
        if (isPartial) {
            headers += HttpHeaders.ContentRange to listOf("bytes $start-$end/$totalLength")
        }
        return headersOf(*headers.toTypedArray())
    }
}

/**
 * The byte range to serve, or null when the request asks for one that does not exist.
 *
 * Null rather than a clamped range on purpose. `coerceRange` used to pull an
 * out-of-range start back down to the last byte, so `bytes=5000-` against a 100 byte
 * file quietly returned byte 99 and called it a success. RFC 7233 wants a 416 there,
 * and a client that asked past the end needs to be told, not handed something else.
 */
internal fun parseRangeHeaders(rangeHeader: String?, totalLength: Long): LongRange? {
    if (rangeHeader == null) return coerceRange(null, null, totalLength)
    if (totalLength <= 0) return null

    val spec = rangeHeader.removePrefix("bytes=").substringBefore(",")
    val start = spec.substringBefore("-").trim().toLongOrNull()
    val end = spec.substringAfter("-", "").trim().toLongOrNull()

    // "bytes=-500" is the LAST 500 bytes, not the first 501. Splitting on "-" and
    // reading the halves positionally turned it into the latter, which hands a player
    // seeking to the end of a file the beginning of it instead.
    if (start == null) {
        if (end == null || end <= 0) return null
        val suffix = end.coerceAtMost(totalLength)
        return LongRange(totalLength - suffix, totalLength - 1)
    }

    if (start >= totalLength) return null
    return coerceRange(start, end, totalLength)
}

internal fun coerceRange(start: Long?, end: Long?, totalLength: Long): LongRange {
    val actualEnd = end?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
    val actualStart = start?.coerceAtLeast(0)?.coerceAtMost(actualEnd) ?: 0

    return LongRange(actualStart, actualEnd)
}
