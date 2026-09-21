import com.bespectacledcyborg.remoteexplorer.data.db.LocalDatabaseDao
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.TagStatus
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class LocalDatabaseDataSource(
    private val localDatabaseDao: LocalDatabaseDao,
    private val dispatcher: CoroutineDispatcher
) {
    fun updateFileList(fileList: List<LocalFile>) {
        localDatabaseDao.updateFileList(fileList)
    }

    suspend fun getFiles(connectionId: Int, parentPath: String, filter: String): List<LocalFile> {
        return withContext(dispatcher) {
            if (filter.isNotBlank()) {
                localDatabaseDao.getFilesWithFilter(connectionId, parentPath, filter)
            } else {
                localDatabaseDao.getFiles(connectionId, parentPath)
            }
        }
    }

    suspend fun getFolderThumbnails(connectionId: Int, parentPath: String): List<String> {
        return withContext(dispatcher) {
            localDatabaseDao.getFolderThumbnails(connectionId, parentPath).map { it.fullPath() }
        }
    }

    suspend fun getProcessedFolderThumbnails(connectionId: Int, parentPath: String): List<String> {
        return withContext(dispatcher) {
            localDatabaseDao.getProcessedFolderThumbnails(connectionId, parentPath)
        }
    }

    suspend fun getTags(): List<LocalTagWithMetadata> {
        return withContext(dispatcher) {
            localDatabaseDao.getTags()
        }
    }

    suspend fun getFilesWithTag(tag: LocalTag): List<LocalFile> {
        return withContext(dispatcher) {
            localDatabaseDao.getFilesWithTag(tag.tag)
        }
    }

    suspend fun updateFileTags(
        connectionId: Int,
        tagFile: LocalFile,
        correspondingFilename: String?,
        tags: List<String>,
    ) {
        withContext(dispatcher) {
            localDatabaseDao.updateFileTags(connectionId, tagFile, correspondingFilename, tags)
        }
    }

    suspend fun updateTagStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
        status: TagStatus,
    ) {
        withContext(dispatcher) {
            localDatabaseDao.updateTagStatus(connectionId, parentPath, filename, status)
        }
    }

    fun updateThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
        status: ThumbnailStatus
    ) {
        localDatabaseDao.updateThumbnailStatus(connectionId, parentPath, filename, status)
    }

    suspend fun getTagFiles(connectionId: Int, forceRemote: Boolean, limit: Int): List<LocalFile> {
        return withContext(dispatcher) {
            if (forceRemote) {
                localDatabaseDao.getAllTagFiles(connectionId, limit)
            } else {
                localDatabaseDao.getUnprocessedTagFiles(connectionId, limit)
            }
        }
    }
}