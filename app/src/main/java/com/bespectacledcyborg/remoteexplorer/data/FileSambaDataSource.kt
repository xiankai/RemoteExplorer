package com.bespectacledcyborg.remoteexplorer.data
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import io.sentry.Sentry
import io.sentry.SentryLevel
import java.io.InputStream

typealias ConnectionId = Int

data class ConnectionDetails(
    override var connectionId: ConnectionId,
    var hostname: String,
    var port: Int,
    var username: String,
    var password: String,
    var domain: String,
    var shareName: String,
) : SourceDetails

data class DiskShareEntry(
    val details: ConnectionDetails,
    val share: DiskShare?
)

class SambaFileDataSource : FileDataSource {
    private var diskShares: MutableMap<ConnectionId, DiskShareEntry> = mutableMapOf()

    override fun connect(details: SourceDetails) {
        require(details is ConnectionDetails) { "Not an SMB share: ${details.connectionId}" }
        openShare(details)
    }

    /**
     * The reconnect path needs the share back, but [FileDataSource.connect] deliberately
     * returns Unit — no caller outside this class uses the handle.
     */
    private fun openShare(details: ConnectionDetails): DiskShare {
        val client = SMBClient()
        val connection = client.connect(details.hostname, details.port)
        val authContext = AuthenticationContext(details.username, details.password.toCharArray(), details.domain)
        val session = connection.authenticate(authContext)
        val share = session.connectShare(details.shareName)
        if (share !is DiskShare) throw IllegalStateException("Not a file share")
        this.diskShares[details.connectionId] = DiskShareEntry(details, share)
        return share
    }

    private fun accessDiskShare(connectionId: ConnectionId): DiskShare {
        val diskShareEntry = this.diskShares[connectionId]
            ?: throw UnknownConnectionException(connectionId, "not initialized")

        if (diskShareEntry.share == null || !diskShareEntry.share.isConnected) {
            return openShare(diskShareEntry.details)
        }

        return diskShareEntry.share
    }

    override fun listFiles(connectionId: ConnectionId, currentPath: String, filter: String): List<File> {
        val files = accessDiskShare(connectionId).list(
            currentPath, filter.ifBlank {
                null
            }
        ).mapNotNull {
            if (it.fileName in listOf(".", "..")) {
                null
            } else {
                File(
                    it.fileName,
                    currentPath,
                    it.lastWriteTime.toDate(),
                    it.allocationSize,
                    it.fileAttributes,
                )
            }
        }
        return files
    }

    override fun openFile(connectionId: ConnectionId, path: String): SourceFile {
        val accessMask = setOf(AccessMask.GENERIC_READ)
        val shareAccesses = setOf(SMB2ShareAccess.FILE_SHARE_READ)
        val createDisposition = SMB2CreateDisposition.FILE_OPEN

        try {
            val diskShare = accessDiskShare(connectionId)
            val file = diskShare.openFile(
                path,
                accessMask,
                null,
                shareAccesses,
                createDisposition,
                null
            )
            Log.d("FileSambaDataSource", "samba has opened file: ${file.uncPath}")
            return SmbSourceFile(file)
        } catch (e: Exception) {
            Log.e("FileSambaDataSource", "samba failed to open file: $path", e)
            if (!e.isMissingFile()) reportOpenFailure(e, connectionId, path)
            throw e
        }
    }

    private fun reportOpenFailure(e: Exception, connectionId: ConnectionId, path: String) {
        val share = diskShares[connectionId]?.details
        Sentry.captureException(e) { scope ->
            scope.level = SentryLevel.WARNING
            scope.setTag("smb.open", e.javaClass.simpleName)
            scope.setExtra(
                "shareName",
                share?.let { "\\\\${it.hostname}\\${it.shareName}" } ?: "connection $connectionId",
            )
            scope.setExtra("fullPath", path)
        }
    }
}

/** A [SourceFile] over an smbj handle. */
private class SmbSourceFile(
    private val file: com.hierynomus.smbj.share.File,
) : SourceFile {
    // endOfFile, not allocationSize: the allocation is rounded up to the share's cluster
    // size, so it is the space the file takes rather than how long it is.
    override fun length(): Long = file.fileInformation.standardInformation.endOfFile

    // smbj's argument order is (buffer, fileOffset, bufferOffset, length), which is the
    // order here too, and it already answers -1 at end of file.
    override fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int =
        file.read(buffer, position, offset, length)

    override fun inputStream(): InputStream = file.inputStream

    override fun close() = file.close()
}
