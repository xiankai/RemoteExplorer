import com.example.remoteexplorer.data.db.LocalDatabaseDao
import com.example.remoteexplorer.data.db.LocalFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class LocalDatabaseDataSource(
    private val localDatabaseDao: LocalDatabaseDao,
    private val dispatcher: CoroutineDispatcher
) {
    fun updateFileList(fileList: List<LocalFile>) {
        localDatabaseDao.updateFileList(fileList)
    }

    suspend fun getFiles(parentPath: String, filter: String?): List<LocalFile> {
        return withContext(dispatcher) {
            if (filter != null) {
                localDatabaseDao.getFilesWithFilter(parentPath, filter)
            } else {
                localDatabaseDao.getFiles(parentPath)
            }
        }
    }

    suspend fun getFolderThumbnails(parentPath: String, limit: Int = 1): List<LocalFile> {
        return withContext(dispatcher) {
            localDatabaseDao.getFolderThumbnails(parentPath, limit)
        }
    }
}