package com.example.remoteexplorer.data

import LocalDatabaseDataSource
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.data.db.LocalFile
import com.example.remoteexplorer.utils.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import java.util.Date

data class File(
    val filename: String,
    val parentPath: String,
    val lastModified: Date,
    val allocationSize: Long,
    val fileAttributes: Long,
)

class FileRepository (
    private val sambaFileDataSource: SambaFileDataSource,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
    private val dispatcher: CoroutineDispatcher
) {
    suspend fun connect(hostname: String, username: String, password: String, domain: String, shareName: String) {
        return withContext(dispatcher) {
            sambaFileDataSource.connect(hostname, username, password, domain, shareName)
        }
    }

    private fun updateRemoteMapping(connectionId: Int, parentPath: String): List<LocalFile> {
        val remoteList = sambaFileDataSource.listFiles(parentPath)
        val mappedRemoteList = remoteList.map {
            LocalFile(
                connectionId = connectionId,
                filename = it.filename,
                parentPath = parentPath,
                type = if (Utils.isDirectory(it.fileAttributes)) { FileType.FOLDER } else { FileType.FILE },
                canHaveThumbnail = Utils.canHaveThumbnail(it.filename),
                lastModified = it.lastModified,
            )
        }
        localDatabaseDataSource.updateFileList(mappedRemoteList)
        return mappedRemoteList
    }

    suspend fun listFiles(connectionId: Int, currentPath: String = "", filter: String? = null, forceRemote: Boolean = false): List<LocalFile> {
        return withContext(dispatcher) {
            if (forceRemote) {
                fetchRemoteFilesAndFilter(connectionId, currentPath, filter)
            } else {
                val localList = localDatabaseDataSource.getFiles(connectionId, currentPath, filter)
                localList.ifEmpty {
                    fetchRemoteFilesAndFilter(connectionId, currentPath, filter)
                }
            }
        }
    }

    private fun fetchRemoteFilesAndFilter(connectionId: Int, currentPath: String = "", filter: String? = null): List<LocalFile> {
        val unfiltered = updateRemoteMapping(connectionId, currentPath)
        return if (filter != null) {
            unfiltered.filter { it.filename.contains(filter) }
        } else {
            unfiltered
        }
    }

    suspend fun getFolderThumbnails(connectionId: Int, folder: String, limit: Int): List<String> {
        return withContext(dispatcher) {
            val localList = localDatabaseDataSource.getFolderThumbnails(connectionId, folder, limit)
            localList.ifEmpty {
                updateRemoteMapping(connectionId, folder)
            }.map {
                Paths.get(folder, it.filename).toString()
            }
        }
    }
}