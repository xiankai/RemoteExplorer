package com.bespectacledcyborg.remoteexplorer.data

import LocalDatabaseDataSource
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.hierynomus.smbj.share.File
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
    suspend fun connect(connectionId: Int, hostname: String, username: String, password: String, domain: String, shareName: String) {
        return withContext(dispatcher) {
            sambaFileDataSource.connect(
                ConnectionDetails(
                    connectionId, hostname, username, password, domain, shareName
            ))
        }
    }

    private fun updateRemoteMapping(connectionId: Int, parentPath: String): List<LocalFile> {
        val remoteList = sambaFileDataSource.listFiles(connectionId, parentPath)
        val mappedRemoteList = remoteList.map {
            LocalFile(
                connectionId = connectionId,
                filename = it.filename,
                parentPath = parentPath,
                type = when {
                    Utils.isDirectory(it.fileAttributes) -> FileType.FOLDER
                    it.filename.endsWith(".xmp", true) -> FileType.TAG
                    else -> FileType.FILE
                },
                canHaveThumbnail = Utils.canHaveThumbnail(it.filename),
                lastModified = it.lastModified,
            )
        }
        localDatabaseDataSource.updateFileList(mappedRemoteList)
        return mappedRemoteList
    }

    suspend fun listFiles(connectionId: Int, currentPath: String = "", filter: String = "", forceRemote: Boolean = false): List<LocalFile> {
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

    private fun fetchRemoteFilesAndFilter(connectionId: Int, currentPath: String = "", filter: String = ""): List<LocalFile> {
        val unfiltered = updateRemoteMapping(connectionId, currentPath)
        return if (filter.isNotBlank()) {
            unfiltered.filter { it.filename.contains(filter) }
        } else {
            unfiltered
        }
    }

    suspend fun getFolderThumbnails(connectionId: Int, folder: String, limit: Int, forceRemote: Boolean): List<String> {
        return withContext(dispatcher) {
            if (forceRemote) {
                updateRemoteMapping(connectionId, folder)
            } else {
                val localList = localDatabaseDataSource.getFiles(connectionId, folder, "")
                localList.ifEmpty {
                    updateRemoteMapping(connectionId, folder)
                }
            }.filter {
                Utils.canHaveThumbnail(it.filename)
            }.map {
                Paths.get(it.parentPath, it.filename).toString()
            }.take(limit)
        }
    }

    suspend fun getTagThumbnails(tag: LocalTag, limit: Int): List<String> {
        return localDatabaseDataSource.getFilesWithTag(tag).filter {
            Utils.canHaveThumbnail(it.filename)
        }.map {
            Paths.get(it.parentPath, it.filename).toString()
        }.take(limit)
    }

    suspend fun getFilesWithTag(tag: LocalTag): List<LocalFile> {
        return localDatabaseDataSource.getFilesWithTag(tag)
    }

    fun openFileDirectly(connectionId: Int, fullPath: String): File {
        return sambaFileDataSource.openFile(connectionId, fullPath)
    }

    fun openFileRange(connectionId: Int, fullPath: String, buffer: ByteArray, start: Int, len: Int): Int {
        val inputStream = sambaFileDataSource.openFile(connectionId, fullPath).inputStream
        inputStream.skipNBytes(start.toLong())
        return inputStream.readNBytes(buffer, 0, len)
    }

    fun getFileSize(connectionId: Int, fullPath: String): Long {
        val file = sambaFileDataSource.openFile(connectionId, fullPath)
        return file.fileInformation.standardInformation.allocationSize
    }
}