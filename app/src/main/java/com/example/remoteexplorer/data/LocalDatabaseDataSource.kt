import com.example.remoteexplorer.data.db.LocalDatabaseDao
import com.example.remoteexplorer.data.db.LocalFile
import com.example.remoteexplorer.data.db.LocalTag
import com.example.remoteexplorer.data.db.LocalTagWithMetadata
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