import com.example.remoteexplorer.data.db.LocalDatabaseDao
import com.example.remoteexplorer.data.db.LocalFile
import com.example.remoteexplorer.data.db.LocalTag
import com.example.remoteexplorer.data.db.TagFileWithCorrespondingFilename
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class LocalDatabaseDataSource(
    private val localDatabaseDao: LocalDatabaseDao,
    private val dispatcher: CoroutineDispatcher
) {
    fun updateFileList(fileList: List<LocalFile>) {
        localDatabaseDao.updateFileList(fileList)
    }

    suspend fun getFiles(connectionId: Int, parentPath: String, filter: String?): List<LocalFile> {
        return withContext(dispatcher) {
            if (filter != null) {
                localDatabaseDao.getFilesWithFilter(connectionId, parentPath, filter)
            } else {
                localDatabaseDao.getFiles(connectionId, parentPath)
            }
        }
    }

    suspend fun getFolderThumbnails(connectionId: Int, parentPath: String, limit: Int = 1): List<LocalFile> {
        return withContext(dispatcher) {
            localDatabaseDao.getFolderThumbnails(connectionId, parentPath, limit)
        }
    }

    suspend fun getTags(): List<LocalTag> {
        return withContext(dispatcher) {
            localDatabaseDao.getTags()
        }
    }

    suspend fun getFilesWithTag(tag: LocalTag): List<LocalFile> {
        return withContext(dispatcher) {
            localDatabaseDao.getFilesWithTag(tag.tag)
        }
    }

    fun updateFileTags(connectionId: Int, parentPath: String, filename: String, tagFilename: String, tags: List<String>) {
        localDatabaseDao.updateFileTags(connectionId, parentPath, filename, tagFilename, tags)
    }

    suspend fun getTagFiles(connectionId: Int, forceRemote: Boolean): List<TagFileWithCorrespondingFilename> {
        return withContext(dispatcher) {
            if (forceRemote) {
                localDatabaseDao.getAllTagFiles(connectionId)
            } else {
                localDatabaseDao.getUnprocessedTagFiles(connectionId)
            }
        }
    }
}