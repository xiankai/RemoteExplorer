package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.bespectacledcyborg.remoteexplorer.utils.Utils.BROWSEABLE_TYPES
import java.nio.file.Paths
import java.util.Date

@Dao
interface LocalDatabaseDao {
    @Query(
        "SELECT LocalFile.* FROM LocalFile " +
                "INNER JOIN FileTagRelation " +
                "ON LocalFile.connectionId = FileTagRelation.connectionId " +
                "AND LocalFile.parentPath = FileTagRelation.parentPath " +
                "AND LocalFile.filename = FileTagRelation.filename " +
                "WHERE FileTagRelation.tag = :tag"
    )
    fun getFilesWithTag(tag: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getFiles(connectionId: Int, parentPath: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND type = :type")
    fun getFolders(
        connectionId: Int,
        parentPath: String,
        type: FileType = FileType.FOLDER
    ): List<LocalFile>

    // '%' || :filter || '%' rather than a bare LIKE :filter, which matched only a filename
    // equal to the search term — i.e. searching for anything short of the exact name found
    // nothing. LIKE is already case-insensitive for ASCII in SQLite.
    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename LIKE '%' || :filter || '%'")
    fun getFilesWithFilter(connectionId: Int, parentPath: String, filter: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath LIMIT :limit")
    fun getFolderThumbnails(connectionId: Int, parentPath: String, limit: Int = 4): List<LocalFile>

    @Query("SELECT filename FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND thumbnailStatus = :thumbnailStatus LIMIT :limit")
    fun getProcessedFolderThumbnails(
        connectionId: Int,
        parentPath: String,
        thumbnailStatus: ThumbnailStatus = ThumbnailStatus.SUCCESS,
        limit: Int = 4
    ): List<String>

    @Query(
        "SELECT LocalTag.tag, COUNT(FileTagRelation.filename) AS count FROM LocalTag " +
                "LEFT JOIN FileTagRelation ON LocalTag.tag = FileTagRelation.tag " +
                "GROUP BY LocalTag.tag"
    )
    fun getTags(): List<LocalTagWithMetadata>

    @Upsert(entity = LocalFile::class)
    fun updateFileList(files: List<LocalFile>)

    @Insert(entity = LocalTag::class, onConflict = OnConflictStrategy.IGNORE)
    fun addTag(tag: LocalTag)

    @Insert(entity = FileTagRelation::class, onConflict = OnConflictStrategy.IGNORE)
    fun addFileTagRelation(relation: FileTagRelation)

    @Query("UPDATE LocalFile SET tagStatus = :status WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    fun updateTagStatus(connectionId: Int, parentPath: String, filename: String, status: TagStatus)

    @Query("UPDATE LocalFile SET thumbnailStatus = :status WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    fun updateThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
        status: ThumbnailStatus
    )

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND type = :type AND tagStatus = :status LIMIT :limit")
    fun getUnprocessedTagFiles(
        connectionId: Int,
        limit: Int,
        type: FileType = FileType.TAG,
        status: TagStatus = TagStatus.UNPROCESSED
    ): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND type = :type LIMIT :limit")
    fun getAllTagFiles(
        connectionId: Int,
        limit: Int,
        type: FileType = FileType.TAG
    ): List<LocalFile>

    /**
     * Records one sidecar's tags against the file it describes.
     *
     * Which file that is used to be decided here, by a `LIKE` query against the directory.
     * That belonged to the source rather than to the DAO — see
     * `com.bespectacledcyborg.remoteexplorer.data.tags.pairSidecarWithMedia`, which also
     * documents the two ways the query got it wrong. [correspondingFilename] is null when the
     * sidecar describes nothing in its directory, which marks it REMOVED.
     */
    @Transaction
    fun updateFileTags(
        connectionId: Int,
        tagFile: LocalFile,
        correspondingFilename: String?,
        tags: List<String>,
    ) {
        val parentPath = tagFile.parentPath

        if (correspondingFilename == null) {
            updateTagStatus(connectionId, parentPath, tagFile.filename, TagStatus.REMOVED)
            return
        }

        tags.forEach {
            addTag(LocalTag(it))
            addFileTagRelation(
                FileTagRelation(connectionId, parentPath, correspondingFilename, it)
            )
        }
        updateTagStatus(connectionId, parentPath, tagFile.filename, TagStatus.SUCCESS)
    }
}

@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
        "filename",
    ]
)
data class LocalFile(
    val connectionId: Int,
    val parentPath: String,
    val filename: String,
    val type: FileType,
    @Deprecated("Using Utils.canHaveThumbnail instead") val canHaveThumbnail: Boolean,
//    val thumbnailPath: String?,
    val tagStatus: TagStatus = TagStatus.UNPROCESSED,
    val thumbnailStatus: ThumbnailStatus = ThumbnailStatus.UNPROCESSED,
    val lastModified: Date,
) {
    fun fullPath(): String {
        return Paths.get(parentPath, filename).toString()
    }

    fun isBrowsable(): Boolean {
        return BROWSEABLE_TYPES.contains<Enum<*>>(type)
    }
}

@Entity
data class LocalTag(
    @PrimaryKey val tag: String,
)

@Entity
data class LocalTagWithMetadata(
    val tag: String,
    val count: Int,
)

@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
        "filename",
        "tag",
    ]
)
data class FileTagRelation(
    val connectionId: Int,
    val parentPath: String,
    val filename: String,
    val tag: String
)

enum class FileType {
    FILE, FOLDER, TAG, CONNECTION, ADD_CONNECTION
}

enum class TagStatus {
    UNPROCESSED, SUCCESS, ERROR, REMOVED
}

enum class ThumbnailStatus {
    UNPROCESSED, SUCCESS, ERROR, UNSUPPORTED  // stack-reviewer-ignore: dropping NESTED cannot orphan a stored row — `git log --all -p -S'NESTED'` shows its only non-declaration use in any commit is a COMPARISON inside the long-disabled thumbnailsJob, and the sole writers of thumbnailStatus (updateRemoteMapping, updateFileThumbnailStatus) pass only the four values above.
}