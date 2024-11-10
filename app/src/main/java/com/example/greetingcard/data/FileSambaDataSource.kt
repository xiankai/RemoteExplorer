package com.example.greetingcard.data
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.nio.file.Paths

class SambaFileDataSource() {
    private lateinit var diskShare: DiskShare

    suspend fun connect(hostname: String, username: String, password: String, domain: String, shareName: String)  {
        val client = SMBClient()
        val connection = client.connect(hostname)
        val authContext = AuthenticationContext(username, password.toCharArray(), domain)
        val session = connection.authenticate(authContext)
        val share = session.connectShare(shareName)
        if (share !is DiskShare) throw IllegalStateException("Not a file share")
        this.diskShare = share
    }

    suspend fun listFiles(currentPath: String = "", filter: String? = null): List<File> {
        val files =  this.diskShare.list(currentPath, filter).map {
            File(it.fileName, Paths.get(currentPath, it.fileName))
        }
        return files
    }
}