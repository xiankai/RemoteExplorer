package com.example.remoteexplorer.data
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare

class SambaFileDataSource {
    private lateinit var diskShare: DiskShare

    fun connect(hostname: String, username: String, password: String, domain: String, shareName: String) {
        val client = SMBClient()
        val connection = client.connect(hostname)
        val authContext = AuthenticationContext(username, password.toCharArray(), domain)
        val session = connection.authenticate(authContext)
        val share = session.connectShare(shareName)
        if (share !is DiskShare) throw IllegalStateException("Not a file share")
        this.diskShare = share
    }

    fun listFiles(currentPath: String = "", filter: String? = null): List<File> {
        val files =  this.diskShare.list(currentPath, filter).mapNotNull {
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

    fun openFile(path: String): com.hierynomus.smbj.share.File {
        val accessMask = setOf(AccessMask.FILE_READ_DATA)
        val attributes = setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL)
        val shareAccesses = setOf(SMB2ShareAccess.FILE_SHARE_READ)
        val createDisposition = SMB2CreateDisposition.FILE_OPEN
        val createOptions = setOf(SMB2CreateOptions.FILE_WRITE_THROUGH)

        try {
            val file = this.diskShare.openFile(path, accessMask, attributes, shareAccesses, createDisposition, createOptions)
            Log.d("FileSambaDataSource", "samba has opened the file. details: ${file.uncPath}")
            return file
        } catch (e: Exception) {
            Log.e("FileSambaDataSource", "samba failed to open $path. details: $e")
            throw e
        }
    }
}