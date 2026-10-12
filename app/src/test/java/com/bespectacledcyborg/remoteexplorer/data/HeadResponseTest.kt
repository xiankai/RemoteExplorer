package com.bespectacledcyborg.remoteexplorer.data

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.routing.Route
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

class HeadResponseTest {

    private val bytes = ByteArray(1_000) { it.toByte() }

    private val files = object : FileDataSource {
        override fun connect(details: SourceDetails) = Unit
        override fun listFiles(connectionId: ConnectionId, currentPath: String, filter: String) =
            emptyList<File>()

        override fun openFile(connectionId: ConnectionId, path: String) = object : SourceFile {
            override fun length() = bytes.size.toLong()
            override fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int {
                val count = minOf(length.toLong(), bytes.size - position).toInt()
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }

            override fun inputStream(): InputStream = ByteArrayInputStream(bytes)
            override fun close() = Unit
        }
    }

    private fun Route.serveHead() {
        head("/{path...}") {
            val path = call.parameters.getAll("path").orEmpty().joinToString("/")
            respondHead(call, files, FileIdentity(1, path), path)
        }
    }

    @Test
    fun `a whole-file HEAD answers 200 with the length and no body`() = testApplication {
        routing { serveHead() }

        val response = client.head("/WEW/HQ.mp4?connectionId=1")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("1000"), response.headers.getAll(HttpHeaders.ContentLength))
        assertEquals("video/mp4", response.headers[HttpHeaders.ContentType])
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `a ranged HEAD answers 206 with the slice it would serve`() = testApplication {
        routing { serveHead() }

        val response = client.head("/WEW/HQ.mp4?connectionId=1") {
            header(HttpHeaders.Range, "bytes=100-199")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals(listOf("100"), response.headers.getAll(HttpHeaders.ContentLength))
        assertEquals("bytes 100-199/1000", response.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `a HEAD past the end answers 416`() = testApplication {
        routing { serveHead() }

        val response = client.head("/WEW/HQ.mp4?connectionId=1") {
            header(HttpHeaders.Range, "bytes=5000-")
        }

        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
        assertEquals("bytes */1000", response.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `Jetty answers a HEAD instead of dropping the connection`() {
        val server = embeddedServer(Jetty, port = 0, host = "127.0.0.1") {
            routing { serveHead() }
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val connection = URI("http://127.0.0.1:$port/WEW/HQ.mp4?connectionId=1").toURL()
                .openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"

            assertEquals(200, connection.responseCode)
            assertEquals("1000", connection.getHeaderField(HttpHeaders.ContentLength))
        } finally {
            server.stop(0, 0)
        }
    }
}
