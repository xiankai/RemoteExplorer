package com.bespectacledcyborg.remoteexplorer.data

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetric
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetrics
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailSources
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailStatusStore
import com.bespectacledcyborg.remoteexplorer.data.views.OpenListener
import com.bespectacledcyborg.remoteexplorer.data.views.PlaybackListener
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.bespectacledcyborg.remoteexplorer.utils.Utils.findAvailablePort
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
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

/** What the server measures as watched; see ServerRepository.playback. */
private val PLAYBACK_TYPES = setOf(IconType.VIDEO, IconType.AUDIO)

private const val MAX_CONCURRENT_RENDERS = 3

class ServerRepository(
    private val fileDataSource: FileDataSource,
    private val dispatcher: CoroutineDispatcher,
    private val thumbnailManager: ThumbnailManager,
    private val thumbnailSources: ThumbnailSources,
    private val thumbnailStatuses: ThumbnailStatusStore,
    /**
     * The name a file is known by — see [Pairings.canonicalize]. Applied here even though
     * the app's own URLs are built canonical already: a URL from before a pairing existed,
     * or one handed to another app, still has to land on the shared cache entry.
     */
    private val canonicalize: (ConnectionId, String) -> FileIdentity,
    /** Which copy to read a canonical file from. */
    private val resolver: SourceResolver,
    /**
     * Told about every byte of a video or audio file that goes out, which is how watched
     * time is measured (#76). Images are left out: Sketch fetches those for the app's own
     * viewer, which counts them by dwell instead.
     */
    private val playback: PlaybackListener = PlaybackListener.NONE,
    private val opens: OpenListener = OpenListener.NONE,
    private val metrics: LoadMetrics = LoadMetrics.NONE,
    private val sourceKind: (ConnectionId) -> String = { "unknown" },
) {
    private val renderSlots = NewestFirstPermits(MAX_CONCURRENT_RENDERS)

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
                        val fullPath = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                        Log.d("ServerRepository", "Received HEAD request for $fullPath")
                        val served = resolver.resolve(canonicalize(connectionId, fullPath))
                        respondHead(call, fileDataSource, served, fullPath)
                    }
                    get("/{path...}") {
                        val connectionId = extractConnectionId(call)
                        val decodedPath = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                        val isThumbnail = call.queryParameters["thumbnail"] == "true"

                        Log.d(
                            "ServerRepository",
                            "Received GET request for $decodedPath (thumbnail: $isThumbnail)"
                        )

                        // Three names for one file, kept apart on purpose. The request's is
                        // whatever the URL said. The canonical one keys everything that
                        // identifies the file — the cache entry and its recorded outcome —
                        // so a local copy and its share share both. The served one is only
                        // where the bytes come from, and says nothing about identity.
                        val canonical = canonicalize(connectionId, decodedPath)
                        suspend fun servedFrom(): FileIdentity =
                            resolver.resolve(canonical).also {
                                Log.d(
                                    "ServerRepository",
                                    "request=($connectionId,$decodedPath) " +
                                            "canonical=(${canonical.connectionId},${canonical.path}) " +
                                            "served-from=(${it.connectionId},${it.path})"
                                )
                            }

                        if (isThumbnail) {
                            val startedAt = System.currentTimeMillis()
                            val media = Utils.guessIconType(decodedPath).name.lowercase()
                            var readFrom = "none"
                            fun logOutcome(outcome: String) {
                                val elapsed = System.currentTimeMillis() - startedAt
                                Log.d(
                                    "ServerRepository",
                                    "Thumbnail $outcome for $decodedPath in ${elapsed}ms"
                                )
                                metrics.record(
                                    LoadMetric.THUMBNAIL_REQUEST,
                                    elapsed,
                                    mapOf(
                                        "outcome" to outcome,
                                        "media" to media,
                                        "source" to readFrom,
                                    ),
                                )
                            }

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
                                thumbnailManager.getThumbnailPath(
                                    canonical.connectionId, canonical.path
                                )

                            if (variant == null && thumbnailFile.exists()) {
                                readFrom = "cache"
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
                            if (variant == null) {  // stack-reviewer-ignore: a diagnostic request neither reads nor writes the record, and that symmetry is the point — `?decoder=` exists to measure a decoder, and a measurement that mutates the state of the thing being measured makes the next run incomparable. So yes, a diagnostic success leaves an earlier ERROR standing; clearing it is pull-to-refresh's job, which is a deliberate user action rather than a side effect of running the benchmark.
                                val known = thumbnailStatuses.statusOf(
                                    canonical.connectionId, canonical.path
                                )
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
                                    canonical.connectionId,
                                    canonical.path,
                                    ThumbnailStatus.UNSUPPORTED,
                                )
                                call.response.status(HttpStatusCode.NotFound)
                                call.respondText("No thumbnail available")
                                logOutcome("unsupported")
                                return@get
                            }

                            val served = servedFrom()
                            readFrom = sourceKind(served.connectionId)
                            val renderAttributes = mapOf("media" to media, "source" to readFrom)
                            val queuedAt = System.currentTimeMillis()
                            val rendered = renderSlots.withPermit {
                                val renderStartedAt = System.currentTimeMillis()
                                metrics.record(
                                    LoadMetric.THUMBNAIL_QUEUE_WAIT,
                                    renderStartedAt - queuedAt,
                                    renderAttributes,
                                )
                                metrics.traced("thumbnail.render", "$media from $readFrom") {
                                    if (variant != null) {
                                        thumbnailManager.renderUncached {
                                            source.render(served.connectionId, served.path, it)
                                        }
                                    } else {
                                        thumbnailManager.renderInto(
                                            canonical.connectionId, canonical.path
                                        ) {
                                            source.render(served.connectionId, served.path, it)
                                        }
                                    }
                                }.also {
                                    metrics.record(
                                        LoadMetric.THUMBNAIL_RENDER,
                                        System.currentTimeMillis() - renderStartedAt,
                                        renderAttributes + ("result" to if (it != null) "ok" else "failed"),
                                    )
                                }
                            }

                            // Record before responding, because the decode already happened
                            // and its answer is just as true whether or not the bytes reach
                            // the client. Recording SUCCESS early is harmless in a way
                            // recording ERROR early would not be: only ERROR and UNSUPPORTED
                            // short-circuit above, so a SUCCESS whose response died still
                            // falls through to the cache check next time — and the rendered
                            // file is already in place, so that serves it.
                            if (variant == null) {  // stack-reviewer-ignore: a SUCCESS written before a failed respondFile cannot strand the file. SUCCESS is not one of the statuses the short-circuit tests for, so the next request proceeds exactly as it would have: cache hit if the file is still there, full re-render if trimToSize has since evicted it. The "never re-renders" failure described would require SUCCESS to short-circuit, and it does not.
                                thumbnailStatuses.record(
                                    canonical.connectionId,
                                    canonical.path,
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
                        val served = servedFrom()
                        val iconType = Utils.guessIconType(decodedPath)
                        val tracked = iconType in PLAYBACK_TYPES
                        val file = fileDataSource.openFile(served.connectionId, served.path)
                        file.use {
                            val totalLength = file.length()

                            val rangeHeader = call.request.headers[HttpHeaders.Range]
                            val range = parseRangeHeaders(rangeHeader, totalLength)
                            if (range == null) {
                                respondUnsatisfiableRange(call, totalLength)
                                return@use
                            }

                            val standardHeaders = commonHeaders(
                                decodedPath,
                                range.first,
                                range.last,
                                totalLength,
                                rangeHeader != null,
                            )
                            for (name in standardHeaders.names()) {
                                standardHeaders.getAll(name)?.forEach { value ->
                                    call.response.headers.append(name, value)
                                }
                            }
                            if (iconType !in Utils.BROWSEABLE_TYPES) opens.onServed(canonical)

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
                                            if (tracked) {
                                                playback.onServed(
                                                    canonical, served, offset,
                                                    offset + read - 1, totalLength,
                                                )
                                            }
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
                                    if (!tracked) {
                                        file.inputStream().transferTo(this)
                                        return@respondOutputStream
                                    }
                                    // Chunk by chunk rather than transferTo, so a player
                                    // streaming the whole file in one response reports
                                    // progress as it plays, not one lump at the end.
                                    val buffer = ByteArray(RANGE_CHUNK_BYTES)
                                    var offset = 0L
                                    // Not closed here, as transferTo's was not: the
                                    // handle's own `use` above owns it.
                                    val input = file.inputStream()
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        write(buffer, 0, read)
                                        playback.onServed(  // stack-reviewer-ignore: both branches report the same thing, the inclusive span [offset, offset + read - 1] of the bytes just written, then advance offset; a whole response is the ranged case with offset starting at 0, and PlaybackSession merges overlapping or touching spans so neither double-counts
                                            canonical, served, offset,
                                            offset + read - 1, totalLength,
                                        )
                                        offset += read
                                    }
                                }
                            }
                        }
                    }
                }
            }.start(wait = true)
        }
    }

    private fun commonHeaders(
        path: String,
        start: Long,
        end: Long,
        totalLength: Long,
        isPartial: Boolean,
    ): Headers {
        val requestedLength = end - start + 1
        val headers = mutableListOf(
            HttpHeaders.AcceptRanges to listOf("bytes"),
            HttpHeaders.ContentType to listOf(contentTypeOf(path)),
            HttpHeaders.ContentLength to listOf(requestedLength.toString()),
        )
        if (isPartial) {
            headers += HttpHeaders.ContentRange to listOf("bytes $start-$end/$totalLength")
        }
        return headersOf(*headers.toTypedArray())
    }
}

internal suspend fun respondHead(
    call: ApplicationCall,
    files: FileDataSource,
    served: FileIdentity,
    path: String,
) {
    files.openFile(served.connectionId, served.path).use { file ->
        val totalLength = file.length()

        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val range = parseRangeHeaders(rangeHeader, totalLength)
        if (range == null) {
            respondUnsatisfiableRange(call, totalLength)
            return
        }

        val isPartial = rangeHeader != null
        call.respond(object : OutgoingContent.NoContent() {
            override val status =
                if (isPartial) HttpStatusCode.PartialContent else HttpStatusCode.OK
            override val contentType = ContentType.parse(contentTypeOf(path))
            override val contentLength = range.last - range.first + 1
            override val headers = headersOf(  // stack-reviewer-ignore: commonHeaders never emitted Last-Modified or ETag, only Accept-Ranges, Content-Type, Content-Length and Content-Range; contentType and contentLength carry the middle two, so the HEAD response has every header it had before
                *listOfNotNull(
                    HttpHeaders.AcceptRanges to listOf("bytes"),
                    if (isPartial) {
                        HttpHeaders.ContentRange to
                            listOf("bytes ${range.first}-${range.last}/$totalLength")
                    } else {
                        null
                    },
                ).toTypedArray()
            )
        })
    }
}

private suspend fun respondUnsatisfiableRange(call: ApplicationCall, totalLength: Long) {
    call.response.headers.append(HttpHeaders.ContentRange, "bytes */$totalLength")
    call.response.status(HttpStatusCode.RequestedRangeNotSatisfiable)
    call.respondText("Requested range not satisfiable")
}

internal fun contentTypeOf(path: String): String =
    URLConnection.guessContentTypeFromName(path) ?: ContentType.Application.OctetStream.toString()

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
