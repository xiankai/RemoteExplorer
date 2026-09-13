package com.bespectacledcyborg.remoteexplorer.data

import LocalDatabaseDataSource
import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Date

data class File(
    val filename: String,
    val parentPath: String,
    val lastModified: Date,
    val allocationSize: Long,
    val fileAttributes: Long,
)

class FileRepository(
    private val sambaFileDataSource: FileDataSource,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
    private val dispatcher: CoroutineDispatcher,
    // Injected purely so this class is reachable from a JVM unit test. The real
    // implementation goes through Sketch's MimeTypeMap, which delegates to
    // android.webkit.MimeTypeMap — a stub that throws under plain unit tests, and returns
    // null (then NPEs inside Sketch) even with unitTests.isReturnDefaultValues. Testing the
    // caching logic should not require Robolectric just to classify a file extension.
    private val canHaveThumbnail: (String) -> Boolean = Utils::canHaveThumbnail,
) {
    suspend fun connect(
        connectionId: Int,
        hostname: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
        shareName: String
    ) {
        return withContext(dispatcher) {
            sambaFileDataSource.connect(
                ConnectionDetails(
                    connectionId, hostname, port, username, password, domain, shareName
                )
            )
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
                thumbnailStatus = if (canHaveThumbnail(it.filename)) {
                    ThumbnailStatus.UNPROCESSED
                } else {
                    ThumbnailStatus.UNSUPPORTED
                },
                canHaveThumbnail = canHaveThumbnail(it.filename),
                lastModified = it.lastModified,
            )
        }
        localDatabaseDataSource.updateFileList(mappedRemoteList)
        return mappedRemoteList
    }

    suspend fun listFiles(
        connectionId: Int,
        currentPath: String = "",
        filter: String = "",
        forceRemote: Boolean = false
    ): List<LocalFile> {
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

    private fun fetchRemoteFilesAndFilter(
        connectionId: Int,
        currentPath: String = "",
        filter: String = ""
    ): List<LocalFile> {
        val unfiltered = updateRemoteMapping(connectionId, currentPath)
        return if (filter.isNotBlank()) {
            // ignoreCase to match the DB path, where SQLite's LIKE is already case-insensitive.
            unfiltered.filter { it.filename.contains(filter, ignoreCase = true) }
        } else {
            unfiltered
        }
    }

    suspend fun getTagThumbnails(tag: LocalTag, limit: Int): List<String> {
        return localDatabaseDataSource.getFilesWithTag(tag).filter {
            Utils.canHaveThumbnail(it.filename)
        }.map {
            it.fullPath()
        }.take(limit)
    }

    suspend fun getFilesWithTag(tag: LocalTag): List<LocalFile> {
        return localDatabaseDataSource.getFilesWithTag(tag)
    }

    fun openFileDirectly(connectionId: Int, fullPath: String): File {
        return sambaFileDataSource.openFile(connectionId, fullPath)
    }

    fun openFileRange(
        connectionId: Int,
        fullPath: String,
        buffer: ByteArray,
        start: Int,
        len: Int
    ): Int {
        val inputStream = sambaFileDataSource.openFile(connectionId, fullPath).inputStream
        inputStream.skipNBytes(start.toLong())
        return inputStream.readNBytes(buffer, 0, len)
    }

    fun getFileSize(connectionId: Int, fullPath: String): Long {
        val file = sambaFileDataSource.openFile(connectionId, fullPath)
        return file.fileInformation.standardInformation.allocationSize
    }

    fun updateFileThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
        thumbnailStatus: ThumbnailStatus
    ) {
        localDatabaseDataSource.updateThumbnailStatus(
            connectionId,
            parentPath,
            filename,
            thumbnailStatus
        )
    }

    suspend fun getFolderThumbnails(connectionId: Int, parentPath: String): List<String> {
        return localDatabaseDataSource.getFolderThumbnails(connectionId, parentPath)
    }

    suspend fun getProcessedFolderThumbnails(connectionId: Int, parentPath: String): List<String> {
        return localDatabaseDataSource.getProcessedFolderThumbnails(connectionId, parentPath)
    }

}