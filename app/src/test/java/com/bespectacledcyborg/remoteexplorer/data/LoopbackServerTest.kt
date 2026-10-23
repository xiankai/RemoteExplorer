package com.bespectacledcyborg.remoteexplorer.data

import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailSource
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailSources
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailStatusStore
import com.bespectacledcyborg.remoteexplorer.data.views.OpenListener
import com.bespectacledcyborg.remoteexplorer.data.views.PlaybackListener
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.bespectacledcyborg.remoteexplorer.utils.encodeUrlPath
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class LoopbackServerTest {

    private val video = ByteArray(10_000) { (it * 7).toByte() }
    private val photo = ByteArray(300) { (it * 3).toByte() }
    private val thumbnail = byteArrayOf(1, 2, 3, 4)

    private val shareFiles = mapOf(
        "Movies/film.mp4" to video,
        "Photos/beach.jpg" to photo,
        "Photos/empty.jpg" to ByteArray(0),
    )

    private val knownConnections = setOf(1)
    private val statuses = RecordingStatuses()
    private val played = mutableListOf<LongRange>()
    private val opened = mutableListOf<FileIdentity>()
    private val renders = mutableListOf<String>()
    private var renderSucceeds = true
    private var filesOpened = 0

    private val files = object : FileDataSource {
        override fun connect(details: SourceDetails) = Unit
        override fun listFiles(connectionId: ConnectionId, currentPath: String, filter: String) =
            emptyList<com.bespectacledcyborg.remoteexplorer.data.File>()

        override fun openFile(connectionId: ConnectionId, path: String): SourceFile {
            filesOpened++
            if (connectionId !in knownConnections) {
                throw UnknownConnectionException(connectionId, "not initialized")
            }
            if (path == "Broken/disk.mp4") throw java.io.IOException("read failed")
            if (path == "Gone/on-the-share.mp4") {
                throw SMBApiException(
                    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value,
                    SMB2MessageCommandCode.SMB2_CREATE,
                    null,
                )
            }
            return InMemoryFile(shareFiles[path] ?: throw FileNotFoundException(path))
        }
    }

    private val token = LoopbackToken.of(ApplicationProvider.getApplicationContext())
    private val auth get() = "token=${token.value}"

    private val thumbnailManager =
        ThumbnailManager(ApplicationProvider.getApplicationContext()) { null }

    private val imageThumbnails = object : ThumbnailSource {
        override suspend fun render(connectionId: Int, path: String, destination: File): Boolean {
            renders += path
            if (renderSucceeds) destination.writeBytes(thumbnail)
            return renderSucceeds
        }
    }

    private val server = ServerRepository(
        fileDataSource = files,
        dispatcher = Dispatchers.Unconfined,
        thumbnailManager = thumbnailManager,
        thumbnailSources = ThumbnailSources { type, _ ->
            if (type == IconType.IMAGE) imageThumbnails else null
        },
        thumbnailStatuses = statuses,
        canonicalize = { id, path -> FileIdentity(id, path) },
        resolver = SourceResolver(
            files,
            pairings = { Pairings.NONE },
            connect = {
                if (it !in knownConnections) throw UnknownConnectionException(it, "does not exist")
            },
        ),
        playback = PlaybackListener { _, _, first, last, _ -> played += first..last },
        opens = OpenListener { opened += it },
        token = token,
    )

    private fun loopback(
        repository: ServerRepository = server,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { with(repository) { serveFiles() } }
        block()
    }

    @Test
    fun `a whole-file GET answers 200 with every byte`() = loopback {
        val response = client.get("/Movies/film.mp4?connectionId=1&$auth")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("10000", response.headers[HttpHeaders.ContentLength])
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
        assertEquals("video/mp4", response.headers[HttpHeaders.ContentType])
        assertNull(response.headers[HttpHeaders.ContentRange])
        assertArrayEquals(video, response.bodyAsBytes())
    }

    @Test
    fun `a bounded range answers 206 with exactly that slice`() = loopback {
        val response = client.get("/Movies/film.mp4?connectionId=1&$auth") {
            header(HttpHeaders.Range, "bytes=1000-1999")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("1000", response.headers[HttpHeaders.ContentLength])
        assertEquals("bytes 1000-1999/10000", response.headers[HttpHeaders.ContentRange])
        assertArrayEquals(video.copyOfRange(1000, 2000), response.bodyAsBytes())
    }

    @Test
    fun `an open-ended range runs to the end of the file`() = loopback {
        val response = client.get("/Movies/film.mp4?connectionId=1&$auth") {
            header(HttpHeaders.Range, "bytes=9000-")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 9000-9999/10000", response.headers[HttpHeaders.ContentRange])
        assertArrayEquals(video.copyOfRange(9000, 10_000), response.bodyAsBytes())
    }

    @Test
    fun `a suffix range is the tail of the file, not the head`() = loopback {
        val response = client.get("/Movies/film.mp4?connectionId=1&$auth") {
            header(HttpHeaders.Range, "bytes=-500")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 9500-9999/10000", response.headers[HttpHeaders.ContentRange])
        assertArrayEquals(video.copyOfRange(9500, 10_000), response.bodyAsBytes())
    }

    @Test
    fun `a range longer than one read chunk arrives whole`() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val bigFiles = object : FileDataSource by files {
            override fun openFile(connectionId: ConnectionId, path: String): SourceFile =
                InMemoryFile(big)
        }
        val bigServer = ServerRepository(
            bigFiles, Dispatchers.Unconfined, thumbnailManager,
            { _, _ -> null }, statuses,
            canonicalize = { id, path -> FileIdentity(id, path) },
            resolver = SourceResolver(bigFiles, { Pairings.NONE }, {}),
            token = token,
        )

        loopback(bigServer) {
            val response = client.get("/Movies/long.mp4?connectionId=1&$auth") {
                header(HttpHeaders.Range, "bytes=1-150000")
            }

            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertArrayEquals(big.copyOfRange(1, 150_001), response.bodyAsBytes())
        }
    }

    @Test
    fun `a range past the end answers 416 and names the real length`() = loopback {
        val response = client.get("/Movies/film.mp4?connectionId=1&$auth") {
            header(HttpHeaders.Range, "bytes=20000-")
        }

        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
        assertEquals("bytes */10000", response.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `any range of an empty file is unsatisfiable`() = loopback {
        val response = client.get("/Photos/empty.jpg?connectionId=1&$auth") {
            header(HttpHeaders.Range, "bytes=0-")
        }

        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
        assertEquals("bytes */0", response.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `HEAD answers with the GET headers and no body`() = loopback {
        val response = client.head("/Movies/film.mp4?connectionId=1&$auth")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("10000", response.headers[HttpHeaders.ContentLength])
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
        assertEquals("video/mp4", response.headers[HttpHeaders.ContentType])
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `a ranged HEAD answers 206 with the slice a GET would send`() = loopback {
        val response = client.head("/Movies/film.mp4?connectionId=1&$auth") {
            header(HttpHeaders.Range, "bytes=-500")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("500", response.headers[HttpHeaders.ContentLength])
        assertEquals("bytes 9500-9999/10000", response.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `a path with spaces and non-ASCII names is decoded before it is opened`() {
        val named = "Photos/海 and sand.jpg"
        val namedFiles = object : FileDataSource by files {
            override fun openFile(connectionId: ConnectionId, path: String): SourceFile =
                if (path == named) InMemoryFile(photo) else throw FileNotFoundException(path)
        }
        val namedServer = ServerRepository(
            namedFiles, Dispatchers.Unconfined, thumbnailManager,
            { _, _ -> null }, statuses,
            canonicalize = { id, path -> FileIdentity(id, path) },
            resolver = SourceResolver(namedFiles, { Pairings.NONE }, {}),
            token = token,
        )

        loopback(namedServer) {
            val response = client.get("/${encodeUrlPath(named)}?connectionId=1&$auth")

            assertEquals(HttpStatusCode.OK, response.status)
            assertArrayEquals(photo, response.bodyAsBytes())
        }
    }

    @Test
    fun `a file that is not there is 404`() = loopback {
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/Movies/missing.mp4?connectionId=1&$auth").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.head("/Movies/missing.mp4?connectionId=1&$auth").status,
        )
    }

    @Test
    fun `a file the share says is not there is 404`() = loopback {
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/Gone/on-the-share.mp4?connectionId=1&$auth").status,
        )
    }

    @Test
    fun `any other failure is still a 500`() = loopback {
        assertEquals(
            HttpStatusCode.InternalServerError,
            client.get("/Broken/disk.mp4?connectionId=1&$auth").status,
        )
        assertEquals(
            HttpStatusCode.InternalServerError,
            client.head("/Broken/disk.mp4?connectionId=1&$auth").status,
        )
    }

    @Test
    fun `an unknown connection is 404`() = loopback {
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/Movies/film.mp4?connectionId=9&$auth").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.head("/Movies/film.mp4?connectionId=9&$auth").status,
        )
    }

    @Test
    fun `video bytes sent are reported as played, images are not`() = loopback {
        client.get("/Movies/film.mp4?connectionId=1&$auth") {
            header(HttpHeaders.Range, "bytes=100-199")
        }
        client.get("/Photos/beach.jpg?connectionId=1&$auth")

        assertEquals(listOf(100L..199L), played)
    }

    @Test
    fun `serving a video counts as an open, serving an image does not`() = loopback {
        client.get("/Photos/beach.jpg?connectionId=1&$auth")
        client.get("/Movies/film.mp4?connectionId=1&$auth")

        assertEquals(listOf(FileIdentity(1, "Movies/film.mp4")), opened)
    }

    @Test
    fun `a thumbnail is rendered once, recorded, then served from the cache`() = loopback {
        val first = client.get("/Photos/beach.jpg?connectionId=1&$auth&thumbnail=true")
        val second = client.get("/Photos/beach.jpg?connectionId=1&$auth&thumbnail=true")

        assertEquals(HttpStatusCode.OK, first.status)
        assertArrayEquals(thumbnail, first.bodyAsBytes())
        assertArrayEquals(thumbnail, second.bodyAsBytes())
        assertEquals(listOf("Photos/beach.jpg"), renders)
        assertEquals(ThumbnailStatus.SUCCESS, statuses.recorded[1 to "Photos/beach.jpg"])
        assertTrue(runBlocking { thumbnailManager.hasThumbnail(1, "Photos/beach.jpg") })
    }

    @Test
    fun `an image with no thumbnail is served whole while every render slot is busy`() {
        val rendering = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : ThumbnailSource {
            override suspend fun render(connectionId: Int, path: String, destination: File): Boolean {
                rendering.complete(Unit)
                release.await()
                destination.writeBytes(thumbnail)
                return true
            }
        }
        val oneSlot = ServerRepository(
            fileDataSource = files,
            dispatcher = Dispatchers.Unconfined,
            thumbnailManager = thumbnailManager,
            thumbnailSources = ThumbnailSources { type, _ ->
                if (type == IconType.IMAGE) gated else null
            },
            thumbnailStatuses = statuses,
            canonicalize = { id, path -> FileIdentity(id, path) },
            resolver = SourceResolver(files, pairings = { Pairings.NONE }, connect = {}),
            token = token,
            renderSlotCount = 1,
        )
        loopback(oneSlot) {
            coroutineScope {
                val url = "/Photos/beach.jpg?connectionId=1&$auth&thumbnail=true"
                val first = async { client.get(url) }
                rendering.await()

                val whileBusy = client.get(url)

                assertEquals(HttpStatusCode.OK, whileBusy.status)
                assertArrayEquals(photo, whileBusy.bodyAsBytes())
                assertEquals("image/jpeg", whileBusy.headers[HttpHeaders.ContentType])

                release.complete(Unit)
                assertArrayEquals(thumbnail, first.await().bodyAsBytes())
            }
        }
    }

    @Test
    fun `a failed render is 404 and is not tried again`() = loopback {
        renderSucceeds = false

        val first = client.get("/Photos/beach.jpg?connectionId=1&$auth&thumbnail=true")
        val second = client.get("/Photos/beach.jpg?connectionId=1&$auth&thumbnail=true")

        assertEquals(HttpStatusCode.NotFound, first.status)
        assertEquals(HttpStatusCode.NotFound, second.status)
        assertEquals(listOf("Photos/beach.jpg"), renders)
        assertEquals(ThumbnailStatus.ERROR, statuses.recorded[1 to "Photos/beach.jpg"])
        assertFalse(runBlocking { thumbnailManager.hasThumbnail(1, "Photos/beach.jpg") })
    }

    @Test
    fun `a type nothing can render is 404 and recorded unsupported`() = loopback {
        val response = client.get("/Movies/film.mp4?connectionId=1&$auth&thumbnail=true")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ThumbnailStatus.UNSUPPORTED, statuses.recorded[1 to "Movies/film.mp4"])
        assertEquals(emptyList<String>(), renders)
    }

    @Test
    fun `no token is 403 for every method, and nothing is read`() = loopback {
        assertEquals(HttpStatusCode.Forbidden, client.get("/Movies/film.mp4?connectionId=1").status)
        assertEquals(HttpStatusCode.Forbidden, client.head("/Movies/film.mp4?connectionId=1").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.options("/Movies/film.mp4?connectionId=1").status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/Photos/beach.jpg?connectionId=1&thumbnail=true").status,
        )

        assertEquals(0, filesOpened)
        assertEquals(emptyList<String>(), renders)
        assertEquals(emptyList<LongRange>(), played)
        assertEquals(emptyList<FileIdentity>(), opened)
    }

    @Test
    fun `a wrong token is 403`() = loopback {
        val wrong = "${token.value.dropLast(1)}x".takeIf { it != token.value } ?: "nope"

        val response = client.get("/Movies/film.mp4?connectionId=1&token=$wrong")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(0, filesOpened)
    }

    @Test
    fun `an empty token is 403`() = loopback {
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/Movies/film.mp4?connectionId=1&token=").status,
        )
    }

    @Test
    fun `the right token is 200`() = loopback {
        assertEquals(
            HttpStatusCode.OK,
            client.get("/Movies/film.mp4?connectionId=1&token=${token.value}").status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.head("/Movies/film.mp4?connectionId=1&token=${token.value}").status,
        )
    }

    @Test
    fun `the token is kept for the install and differs between installs`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = LoopbackToken.of(context).value
        val again = LoopbackToken.of(context).value
        val elsewhere = LoopbackToken(
            context.getSharedPreferences("another_install", android.content.Context.MODE_PRIVATE)
        ).value

        assertEquals(first, again)
        assertTrue(first != elsewhere)
        assertTrue(first.length >= 43)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `a URL the app builds is served through Jetty`() {
        val jetty = embeddedServer(Jetty, port = 0, host = "127.0.0.1") {
            with(server) { serveFiles() }
        }.start(wait = false)
        try {
            val port = runBlocking { jetty.engine.resolvedConnectors().single().port }
            val built = URI(Utils.constructUrl(1, "Movies/film.mp4", server.urlToken))
            val connection = URI("http://127.0.0.1:$port${built.rawPath}?${built.rawQuery}")
                .toURL().openConnection() as HttpURLConnection

            assertEquals(200, connection.responseCode)

            val bare = URI("http://127.0.0.1:$port${built.rawPath}?connectionId=1")
                .toURL().openConnection() as HttpURLConnection
            bare.requestMethod = "HEAD"
            assertEquals(403, bare.responseCode)
        } finally {
            jetty.stop(0, 0)
        }
    }

    @Test
    fun `Jetty serves a ranged GET end to end`() {
        val jetty = embeddedServer(Jetty, port = 0, host = "127.0.0.1") {
            with(server) { serveFiles() }
        }.start(wait = false)
        try {
            val port = runBlocking { jetty.engine.resolvedConnectors().single().port }
            val connection = URI("http://127.0.0.1:$port/Movies/film.mp4?connectionId=1&$auth")
                .toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty(HttpHeaders.Range, "bytes=-500")

            assertEquals(206, connection.responseCode)
            assertEquals("bytes 9500-9999/10000", connection.getHeaderField(HttpHeaders.ContentRange))
            assertArrayEquals(
                video.copyOfRange(9500, 10_000),
                connection.inputStream.use { it.readBytes() },
            )
        } finally {
            jetty.stop(0, 0)
        }
    }

    private class InMemoryFile(private val bytes: ByteArray) : SourceFile {
        override fun length() = bytes.size.toLong()
        override fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun inputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun close() = Unit
    }

    private class RecordingStatuses : ThumbnailStatusStore {
        val recorded = ConcurrentHashMap<Pair<Int, String>, ThumbnailStatus>()

        override suspend fun statusOf(connectionId: Int, path: String) =
            recorded[connectionId to path]

        override suspend fun record(connectionId: Int, path: String, status: ThumbnailStatus) {
            recorded[connectionId to path] = status
        }
    }
}
