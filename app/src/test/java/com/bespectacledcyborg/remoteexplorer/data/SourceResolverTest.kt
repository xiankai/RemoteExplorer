package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Where a request is actually read from: the local copy when it has the file, the share
 * otherwise — and never a failed request just because the copy could not answer.
 */
class SourceResolverTest {

    private val pairings = Pairings(listOf(Pairing(3, "", 1, "Library")))
    private val remote = FileIdentity(1, "Library/beach.jpg")
    private val opened = mutableListOf<ConnectionId>()
    private val logged = mutableListOf<FileIdentity>()

    private fun resolver(files: FileDataSource) = SourceResolver(
        files,
        pairings = { pairings },
        connect = { opened += it },
        onUnreadable = { copy, _ -> logged += copy },
    )

    @Test
    fun `reads the local copy when it is there`() = runTest {
        val served = resolver(Files { _, _ -> }).resolve(remote)

        assertEquals(FileIdentity(3, "beach.jpg"), served)
        assertEquals("the share is not opened for a file the device has", listOf(3), opened)
    }

    @Test
    fun `falls back to the share when the copy does not have the file`() = runTest {
        val served = resolver(Files { id, _ -> if (id == 3) throw FileNotFoundException() })
            .resolve(remote)

        assertEquals(remote, served)
        assertEquals(listOf(3, 1), opened)
        assertEquals("missing is ordinary and not worth a line", emptyList<FileIdentity>(), logged)
    }

    @Test
    fun `falls back to the share when the copy cannot be read, and says so`() = runTest {
        val served = resolver(Files { id, _ -> if (id == 3) throw IOException("card ejected") })
            .resolve(remote)

        assertEquals(remote, served)
        assertEquals(listOf(FileIdentity(3, "beach.jpg")), logged)
    }

    @Test
    fun `a local root that cannot be opened falls back to the share`() = runTest {
        val served = SourceResolver(
            Files { _, _ -> },
            pairings = { pairings },
            connect = { if (it == 3) throw IllegalStateException("access revoked") },
            onUnreadable = { copy, _ -> logged += copy },
        ).resolve(remote)

        assertEquals(remote, served)
        assertEquals(listOf(FileIdentity(3, "beach.jpg")), logged)
    }

    @Test
    fun `cancellation is not mistaken for a missing copy`() = runTest {
        val thrown = runCatching {
            SourceResolver(
                Files { _, _ -> },
                pairings = { pairings },
                connect = { if (it == 3) throw kotlinx.coroutines.CancellationException("gone") else opened += it },
            ).resolve(remote)
        }.exceptionOrNull()

        assertTrue(thrown is kotlinx.coroutines.CancellationException)
        assertEquals("the share must not be reached for a cancelled request", emptyList<ConnectionId>(), opened)
    }

    @Test
    fun `an unpaired file is read where it is`() = runTest {
        val elsewhere = FileIdentity(1, "Other/a.jpg")
        assertEquals(elsewhere, resolver(Files { _, _ -> }).resolve(elsewhere))
        assertEquals(listOf(1), opened)
    }

    /** Opens anything [check] does not throw for, as an empty file. */
    private class Files(private val check: (ConnectionId, String) -> Unit) : FileDataSource {
        override fun connect(details: SourceDetails) = Unit
        override fun listFiles(connectionId: ConnectionId, currentPath: String, filter: String) =
            emptyList<File>()

        override fun openFile(connectionId: ConnectionId, path: String): SourceFile {
            check(connectionId, path)
            return object : SourceFile {
                override fun length() = 0L
                override fun read(buffer: ByteArray, position: Long, offset: Int, length: Int) = -1
                override fun inputStream(): InputStream = InputStream.nullInputStream()
                override fun close() = Unit
            }
        }
    }
}

class ConnectionOpenerTest {
    private val local = RecordingSource()
    private val routing = RoutingFileDataSource(smb = RecordingSource(), local = local)

    @Test
    fun `opens a connection once, however many ask at the same time`() = runTest {
        var opens = 0
        val opener = ConnectionOpener(
            routing,
            lookUp = { localRoot(it) },
            open = { opens++; routing.connect(LocalRootDetails(it.id, it.localRoot!!)) },
        )

        kotlinx.coroutines.coroutineScope {
            repeat(20) { launch { opener.ensureOpen(3) } }
        }
        assertEquals(1, opens)
    }

    @Test
    fun `a failed open is tried again next time`() = runTest {
        var attempts = 0
        val opener = ConnectionOpener(
            routing,
            lookUp = { localRoot(it) },
            open = {
                attempts++
                if (attempts == 1) throw IOException("share asleep")
                routing.connect(LocalRootDetails(it.id, it.localRoot!!))
            },
        )

        runCatching { opener.ensureOpen(3) }
        opener.ensureOpen(3)
        assertEquals(2, attempts)
    }

    private fun localRoot(id: Int) = com.bespectacledcyborg.remoteexplorer.data.db.Connection(
        id = id, name = "phone",
        type = com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType.LOCAL,
        hostname = null, username = null, password = null, domain = null, share = null,
        localRoot = "/sdcard/Sync", lastModified = java.util.Date(0),
    )

    private class RecordingSource : FileDataSource {
        override fun connect(details: SourceDetails) = Unit
        override fun listFiles(connectionId: ConnectionId, currentPath: String, filter: String) =
            emptyList<File>()
        override fun openFile(connectionId: ConnectionId, path: String): SourceFile =
            throw UnsupportedOperationException()
    }
}
