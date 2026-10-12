import com.bespectacledcyborg.remoteexplorer.data.db.DirTagRelation
import com.bespectacledcyborg.remoteexplorer.data.db.DirectoryTagUpdate
import com.bespectacledcyborg.remoteexplorer.data.db.FileTagUpdate
import com.bespectacledcyborg.remoteexplorer.data.db.LocalDatabaseDao
import com.bespectacledcyborg.remoteexplorer.data.db.TagAlias
import com.bespectacledcyborg.remoteexplorer.data.db.TagAliasPair
import com.bespectacledcyborg.remoteexplorer.data.db.TagIndexIngest
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.TagStatus
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

    suspend fun getFolderThumbnails(
        connectionId: Int,
        parentPath: String,
        limit: Int = 4,
    ): List<String> {
        return withContext(dispatcher) {
            localDatabaseDao.getFolderThumbnails(connectionId, parentPath, limit).map { it.fullPath() }
        }
    }

    suspend fun getProcessedFolderThumbnails(connectionId: Int, parentPath: String): List<String> {
        return withContext(dispatcher) {
            localDatabaseDao.getProcessedFolderThumbnails(connectionId, parentPath)
        }
    }

    suspend fun getTagIndexIngest(connectionId: Int): TagIndexIngest? =
        withContext(dispatcher) { localDatabaseDao.getTagIndexIngest(connectionId) }

    /** One pass over the rolled-up index, rows and resume offset together. See the DAO. */
    suspend fun applyTagIndex(
        connectionId: Int,
        replace: Boolean,
        directories: List<DirectoryTagUpdate>,
        files: List<FileTagUpdate>,
        generation: String,
        bytesRead: Long,
    ) = withContext(dispatcher) {
        localDatabaseDao.applyTagIndex(
            connectionId, replace, directories, files, generation, bytesRead
        )
    }

    suspend fun getTagAliasGeneration(connectionId: Int): String? =
        withContext(dispatcher) { localDatabaseDao.getTagAliasGeneration(connectionId) }

    /** One connection's alias groups, swapped for a new generation. See the DAO. */
    suspend fun replaceTagAliases(
        connectionId: Int,
        aliases: List<TagAlias>,
        generation: String,
        hidden: List<String> = emptyList(),
    ) = withContext(dispatcher) {
        localDatabaseDao.replaceTagAliases(connectionId, aliases, generation, hidden)
    }

    suspend fun getTags(): List<LocalTagWithMetadata> {
        return withContext(dispatcher) {
            localDatabaseDao.getTags()
        }
    }

    suspend fun getTagAliasPairs(): List<TagAliasPair> =
        withContext(dispatcher) { localDatabaseDao.getTagAliasPairs() }

    suspend fun getFilesWithTag(tag: LocalTag): List<LocalFile> {
        return withContext(dispatcher) {
            localDatabaseDao.getFilesWithTag(tag.tag)
        }
    }

    suspend fun getDirectoriesWithTag(tag: LocalTag): List<DirTagRelation> {
        return withContext(dispatcher) {
            localDatabaseDao.getDirectoriesWithTag(tag.tag)
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

    suspend fun getThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
    ): ThumbnailStatus? = withContext(dispatcher) {
        localDatabaseDao.getThumbnailStatus(connectionId, parentPath, filename)
    }

    fun clearFailedThumbnails(connectionId: Int, parentPath: String) {
        localDatabaseDao.clearFailedThumbnails(connectionId, parentPath)
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