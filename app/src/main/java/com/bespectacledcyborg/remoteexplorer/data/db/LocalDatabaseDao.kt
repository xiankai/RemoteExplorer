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

    // --- Directory reconciliation -------------------------------------------------------
    //
    // A refresh used to be a single @Upsert of the whole remote listing, which wrote every
    // column — including tagStatus and thumbnailStatus, rebuilt as UNPROCESSED by
    // FileRepository.updateRemoteMapping. So one pull-to-refresh discarded the derived state
    // of every file in the directory, and the app re-generated every thumbnail and re-read
    // every .xmp sidecar for a listing that had usually not changed at all. Upsert also has
    // no way to notice a deletion, so files removed on the server stayed in the cache
    // forever.
    //
    // reconcileDirectory replaces it: insert what is new, touch only the columns the server
    // owns on what already exists, and delete what the server no longer lists.

    @Query("SELECT filename, lastModified FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getFileStamps(connectionId: Int, parentPath: String): List<LocalFileStamp>

    /**
     * IGNORE rather than REPLACE: a row that already exists carries derived state this
     * listing knows nothing about, and is handled by [refreshChangedFile] instead.
     */
    @Insert(entity = LocalFile::class, onConflict = OnConflictStrategy.IGNORE)
    fun insertNewFiles(files: List<LocalFile>)

    /**
     * Applied only to a file whose [LocalFile.lastModified] actually moved, so the thumbnail
     * and the tags are re-derived when the bytes changed and left alone when they did not.
     */
    @Query(
        "UPDATE LocalFile SET type = :type, lastModified = :lastModified, " +
                "thumbnailStatus = :thumbnailStatus, tagStatus = :tagStatus " +
                "WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename"
    )
    fun refreshChangedFile(
        connectionId: Int,
        parentPath: String,
        filename: String,
        type: FileType,
        lastModified: Date,
        thumbnailStatus: ThumbnailStatus,
        tagStatus: TagStatus = TagStatus.UNPROCESSED,
    )

    @Query("DELETE FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename IN (:filenames)")
    fun deleteFiles(connectionId: Int, parentPath: String, filenames: List<String>)

    // Left behind, a relation still counts towards LocalTag's total in getTags(), which
    // joins on FileTagRelation rather than on LocalFile — so a deleted file would keep
    // inflating its tags' counts.
    @Query("DELETE FROM FileTagRelation WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename IN (:filenames)")
    fun deleteFileTagRelations(connectionId: Int, parentPath: String, filenames: List<String>)

    @Query("SELECT * FROM DirectorySync WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getDirectorySync(connectionId: Int, parentPath: String): DirectorySync?

    @Upsert(entity = DirectorySync::class)
    fun upsertDirectorySync(sync: DirectorySync)

    /**
     * Brings one directory's cached rows in line with [remote] and records the sync, all in
     * one transaction so a failure part-way cannot leave the cache claiming to be fresher
     * than it is.
     */
    @Transaction
    fun reconcileDirectory(
        connectionId: Int,
        parentPath: String,
        remote: List<LocalFile>,
        syncedAt: Date,
    ) {
        val cached = getFileStamps(connectionId, parentPath).associateBy { it.filename }
        val (known, added) = remote.partition { cached.containsKey(it.filename) }

        insertNewFiles(added)

        known.forEach { file ->
            if (cached.getValue(file.filename).lastModified != file.lastModified) {
                refreshChangedFile(
                    connectionId = connectionId,
                    parentPath = parentPath,
                    filename = file.filename,
                    type = file.type,
                    lastModified = file.lastModified,
                    thumbnailStatus = file.thumbnailStatus,
                )
            }
        }

        val listed = remote.mapTo(mutableSetOf()) { it.filename }
        // Chunked because the filenames become bind parameters, and SQLite caps how many a
        // single statement may carry. A directory can hold more files than that cap.
        (cached.keys - listed).chunked(DELETE_CHUNK).forEach { removed ->
            deleteFileTagRelations(connectionId, parentPath, removed)
            deleteFiles(connectionId, parentPath, removed)
        }

        upsertDirectorySync(DirectorySync(connectionId, parentPath, syncedAt))
    }

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

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND type != :tagFileType AND filename LIKE :filenamePattern LIMIT 1")
    fun getCorrespondingFileForTagFile(
        connectionId: Int,
        parentPath: String,
        tagFileType: FileType = FileType.TAG,
        filenamePattern: String
    ): LocalFile?

    @Transaction
    fun updateFileTags(connectionId: Int, tagFile: LocalFile, tags: List<String>) {
        // Find corresponding tag file
        val filenamePattern = tagFile.filename.substringBeforeLast('.').plus(".%")
        val parentPath = tagFile.parentPath
        val correspondingFile = getCorrespondingFileForTagFile(
            connectionId,
            parentPath,
            filenamePattern = filenamePattern
        )

        // If cannot find, mark as REMOVED
        if (correspondingFile == null) {
            updateTagStatus(connectionId, parentPath, tagFile.filename, TagStatus.REMOVED)
            return
        }

        // Add tag mappings
        tags.map {
            addTag(LocalTag(it))
            addFileTagRelation(
                FileTagRelation(
                    connectionId,
                    parentPath,
                    correspondingFile.filename,
                    it
                )
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

/**
 * When a directory was last reconciled against the share.
 *
 * Its absence is the only thing that distinguishes "never fetched" from "fetched, and the
 * folder is genuinely empty" — a distinction the cache could not previously make, so an
 * empty folder re-hit SMB on every single visit.
 */
@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
    ]
)
data class DirectorySync(
    val connectionId: Int,
    val parentPath: String,
    val lastSyncedAt: Date,
)

/** Just enough of a cached row to decide insert / update / delete during a reconcile. */
data class LocalFileStamp(
    val filename: String,
    val lastModified: Date,
)

/**
 * How many filenames may go into one `IN (...)` clause. SQLite's default ceiling on bind
 * parameters is 999; staying well under it leaves room for the statement's other arguments.
 */
private const val DELETE_CHUNK = 900

enum class FileType {
    FILE, FOLDER, TAG, CONNECTION, ADD_CONNECTION
}

enum class TagStatus {
    UNPROCESSED, SUCCESS, ERROR, REMOVED
}

enum class ThumbnailStatus {
    UNPROCESSED, SUCCESS, ERROR, UNSUPPORTED  // stack-reviewer-ignore: dropping NESTED cannot orphan a stored row — `git log --all -p -S'NESTED'` shows its only non-declaration use in any commit is a COMPARISON inside the long-disabled thumbnailsJob, and the sole writers of thumbnailStatus (updateRemoteMapping, updateFileThumbnailStatus) pass only the four values above.
}