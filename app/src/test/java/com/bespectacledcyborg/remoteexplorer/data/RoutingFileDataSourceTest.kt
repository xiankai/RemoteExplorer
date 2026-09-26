package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Everything that reads a file holds one [RoutingFileDataSource] and names files by
 * connection id alone, so sending an id to the wrong source is the whole failure mode.
 */
class RoutingFileDataSourceTest {

    private val smb = RecordingSource()
    private val local = RecordingSource()
    private val routing = RoutingFileDataSource(smb, local)

    private val share = ConnectionDetails(1, "nas", 445, "u", "p", "", "media")
    private val folder = LocalRootDetails(2, "/storage/1234-ABCD/Sync")

    @Test
    fun `each connection is served by the source it connected to`() {
        routing.connect(share)
        routing.connect(folder)

        routing.listFiles(1, "Photos")
        routing.listFiles(2, "Photos")

        assertEquals(listOf(1), smb.listed)
        assertEquals(listOf(2), local.listed)
    }

    @Test
    fun `a connection that has not connected is refused rather than guessed at`() {
        val thrown = runCatching { routing.listFiles(1, "") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `a failed connect leaves no route behind`() {
        local.failConnect = true
        runCatching { routing.connect(folder) }

        assertTrue(runCatching { routing.listFiles(2, "") }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `a share connection carries its SMB fields`() {
        val details = sourceDetailsOf(connection(ConnectionType.SMB))

        assertEquals(ConnectionDetails(5, "nas", 1445, "explorer", "secret", "", "media"), details)
    }

    @Test
    fun `a local connection carries only its folder`() {
        val details = sourceDetailsOf(
            connection(ConnectionType.LOCAL).copy(
                hostname = null, username = null, password = null, domain = null, share = null,
                localRoot = "/storage/emulated/0/Sync",
            )
        )

        assertEquals(LocalRootDetails(5, "/storage/emulated/0/Sync"), details)
    }

    @Test
    fun `a local connection with no folder says so`() {
        val thrown = runCatching { sourceDetailsOf(connection(ConnectionType.LOCAL)) }
            .exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    private fun connection(type: ConnectionType) = Connection(
        id = 5,
        name = "nas",
        type = type,
        hostname = "nas",
        port = 1445,
        username = "explorer",
        password = SecretText("secret"),
        domain = "",
        share = "media",
        lastModified = Date(0),
    )

    private class RecordingSource : FileDataSource {
        val listed = mutableListOf<ConnectionId>()
        var failConnect = false

        override fun connect(details: SourceDetails) {
            if (failConnect) throw IllegalStateException("cannot")
        }

        override fun listFiles(connectionId: ConnectionId, currentPath: String, filter: String) =
            emptyList<File>().also { listed += connectionId }

        override fun openFile(connectionId: ConnectionId, path: String): SourceFile =
            throw UnsupportedOperationException()
    }
}
