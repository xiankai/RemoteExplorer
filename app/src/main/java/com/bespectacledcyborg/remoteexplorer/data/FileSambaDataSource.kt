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

typealias ConnectionId = Int

data class ConnectionDetails(
    var connectionId: ConnectionId,
    var hostname: String,
    var username: String,
    var password: String,
    var domain: String,
    var shareName: String,
)

data class DiskShareEntry(
    val details: ConnectionDetails,
    val share: DiskShare?
)

class SambaFileDataSource {
    private var diskShares: MutableMap<ConnectionId, DiskShareEntry> = mutableMapOf()

    fun connect(
        details: ConnectionDetails
    ): DiskShare {
        val client = SMBClient()
        val connection = client.connect(details.hostname)
        val authContext = AuthenticationContext(details.username, details.password.toCharArray(), details.domain)
        val session = connection.authenticate(authContext)
        val share = session.connectShare(details.shareName)
        if (share !is DiskShare) throw IllegalStateException("Not a file share")
        this.diskShares[details.connectionId] = DiskShareEntry(details, share)
        return share
    }

    private fun accessDiskShare(connectionId: ConnectionId): DiskShare {
        val diskShareEntry = this.diskShares[connectionId]
            ?: throw IllegalStateException("Connection ID: $connectionId not initialized")

        if (diskShareEntry.share == null) {
            return connect(diskShareEntry.details)
        }

        return diskShareEntry.share
    }

    fun listFiles(connectionId: ConnectionId, currentPath: String = "", filter: String = ""): List<File> {
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

    fun openFile(connectionId: ConnectionId, path: String): com.hierynomus.smbj.share.File {
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
            return file
        } catch (e: Exception) {
            Log.e("FileSambaDataSource", "samba failed to open file: $path", e)
            Sentry.captureMessage("Failed to open", SentryLevel.INFO, {
                val diskShare = accessDiskShare(connectionId)
                it.setExtra("shareName", diskShare.smbPath.toString())
                it.setExtra("fullPath", path)
            })
            throw e
        }
    }
}