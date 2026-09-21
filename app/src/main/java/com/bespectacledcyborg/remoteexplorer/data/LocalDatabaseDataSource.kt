import com.bespectacledcyborg.remoteexplorer.data.db.LocalDatabaseDao
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Date

class LocalDatabaseDataSource(
    private val localDatabaseDao: LocalDatabaseDao,
    private val dispatcher: CoroutineDispatcher
) {
    fun reconcileDirectory(
        connectionId: Int,
        parentPath: String,
        remote: List<LocalFile>,
        syncedAt: Date,
    ) {
        localDatabaseDao.reconcileDirectory(connectionId, parentPath, remote, syncedAt)
    }

    suspend fun getLastSyncedAt(connectionId: Int, parentPath: String): Date? {
        return withContext(dispatcher) {
            localDatabaseDao.getDirectorySync(connectionId, parentPath)?.lastSyncedAt
        }
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

    fun updateFileTags(connectionId: Int, tagFile: LocalFile, tags: List<String>) {
        localDatabaseDao.updateFileTags(connectionId, tagFile, tags)
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