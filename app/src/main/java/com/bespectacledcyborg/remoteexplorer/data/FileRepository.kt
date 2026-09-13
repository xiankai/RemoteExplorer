package com.bespectacledcyborg.remoteexplorer.data

import LocalDatabaseDataSource
import android.media.MediaDataSource
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
    private val sambaFileDataSource: SambaFileDataSource,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
    private val dispatcher: CoroutineDispatcher
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
                thumbnailStatus = if (Utils.canHaveThumbnail(it.filename)) {
                    ThumbnailStatus.UNPROCESSED
                } else {
                    ThumbnailStatus.UNSUPPORTED
                },
                canHaveThumbnail = Utils.canHaveThumbnail(it.filename),
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
            unfiltered.filter { it.filename.contains(filter) }
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

    fun getDataSource(connectionId: Int, fullPath: String): MediaDataSource {
        return object : MediaDataSource() {
            val fileSize = sambaFileDataSource.openFile(connectionId, fullPath).use {
                it.fileInformation.standardInformation.allocationSize
            }

            // Lock for thread safety
            private val lock = Any()

            override fun close() {
                // Nothing to close at the class level
                // Each readAt operation uses and closes its own stream
            }

            override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
                if (buffer == null) {
                    Log.e("MediaDataSource", "Buffer is null")
                    return -1
                }

                if (position < 0 || position >= fileSize) {
                    Log.d(
                        "MediaDataSource",
                        "Invalid position request: $position (size: $fileSize)"
                    )
                    return 0  // EOF
                }

                synchronized(lock) {
                    try {
                        // For each readAt call, open a new stream and seek to the position
                        val file = sambaFileDataSource.openFile(connectionId, fullPath)
                        file.inputStream.use { stream ->
                            // Skip to the requested position
                            stream.skipNBytes(position)

                            // Calculate how many bytes we can actually read
                            val bytesToRead = minOf(size.toLong(), fileSize - position).toInt()

                            // Read directly into the provided buffer at the specified offset
                            var totalRead = 0
                            var bytesRead: Int

                            while (totalRead < bytesToRead) {
                                bytesRead =
                                    stream.read(buffer, offset + totalRead, bytesToRead - totalRead)

                                if (bytesRead == -1) {
                                    // End of file reached
                                    break
                                }

                                totalRead += bytesRead
                            }

                            return totalRead
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "MediaDataSource",
                            "Error reading at position $position: ${e.message}",
                            e
                        )
                        return -1
                    }
                }
            }

            override fun getSize(): Long {
                return fileSize
            }
        }
    }

}