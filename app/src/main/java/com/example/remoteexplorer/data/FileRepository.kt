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

    private fun updateRemoteMapping(parentPath: String): List<LocalFile> {
        val remoteList = sambaFileDataSource.listFiles(parentPath)
        val mappedRemoteList = remoteList.map {
            LocalFile(
                filename = it.filename,
                parentPath = parentPath,
                type = if (Utils.isDirectory(it.fileAttributes)) { FileType.FOLDER } else { FileType.FILE },
//                type = if (it.allocationSize == 0L) { FileType.FOLDER } else { FileType.FILE },
                canHaveThumbnail = Utils.canHaveThumbnail(it.filename),
                lastModified = it.lastModified,
            )
        }
        localDatabaseDataSource.updateFileList(mappedRemoteList)
        return mappedRemoteList
    }

    suspend fun listFiles(currentPath: String = "", filter: String? = null, forceRemote: Boolean = false): List<LocalFile> {
        return withContext(dispatcher) {
            if (forceRemote) {
                fetchRemoteFilesAndFilter(currentPath, filter)
            } else {
                val localList = localDatabaseDataSource.getFiles(currentPath, filter)
                localList.ifEmpty {
                    fetchRemoteFilesAndFilter(currentPath, filter)
                }
            }
        }
    }

    private suspend fun fetchRemoteFilesAndFilter(currentPath: String = "", filter: String? = null): List<LocalFile> {
        val unfiltered = updateRemoteMapping(currentPath)
        return if (filter != null) {
            unfiltered.filter { it.filename.contains(filter) }
        } else {
            unfiltered
        }
    }

    suspend fun getFolderThumbnails(folder: String, limit: Int): List<String> {
        return withContext(dispatcher) {
            val localList = localDatabaseDataSource.getFolderThumbnails(folder, limit)
            localList.ifEmpty {
                updateRemoteMapping(folder)
            }.map {
                Paths.get(folder, it.filename).toString()
            }
        }
    }
}