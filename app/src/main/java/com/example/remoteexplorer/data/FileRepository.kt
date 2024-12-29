package com.example.remoteexplorer.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Date

data class File(
    val filename: String,
    val fullPath: String,
    val lastModified: Date,
)

class FileRepository (
    private val sambaFileDataSource: SambaFileDataSource,
    private val dispatcher: CoroutineDispatcher
) {
    suspend fun connect(hostname: String, username: String, password: String, domain: String, shareName: String) {
        return withContext(dispatcher) {
            this@FileRepository.sambaFileDataSource.connect(hostname, username, password, domain, shareName)
        }
    }

    suspend fun listFiles(currentPath: String = "", filter: String? = null): List<File> {
        return withContext(dispatcher) {
            this@FileRepository.sambaFileDataSource.listFiles(currentPath, filter)
        }
    }

    suspend fun openFile(currentPath: String): com.hierynomus.smbj.share.File {
        return withContext(dispatcher) {
            this@FileRepository.sambaFileDataSource.openFile(currentPath)
        }
    }
}