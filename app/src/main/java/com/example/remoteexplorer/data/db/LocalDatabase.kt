package com.example.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import java.util.Date

@Dao
interface LocalDatabaseDao {
    @Query("SELECT LocalFile.* FROM LocalFile " +
            "INNER JOIN FileTagRelation " +
            "ON LocalFile.connectionId = FileTagRelation.connectionId " +
            "AND LocalFile.parentPath = FileTagRelation.parentPath " +
            "AND LocalFile.filename = FileTagRelation.filename " +
            "WHERE FileTagRelation.tag = :tag")
    fun getFilesWithTag(tag: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getFiles(connectionId: Int, parentPath: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename LIKE :filter")
    fun getFilesWithFilter(connectionId: Int, parentPath: String, filter: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND canHaveThumbnail = TRUE LIMIT :limit")
    fun getFolderThumbnails(connectionId: Int, parentPath: String, limit: Int): List<LocalFile>

    @Query("SELECT LocalTag.tag, COUNT(FileTagRelation.filename) AS count FROM LocalTag " +
            "LEFT JOIN FileTagRelation ON LocalTag.tag = FileTagRelation.tag " +
            "GROUP BY LocalTag.tag")
    fun getTags(): List<LocalTagWithMetadata>

    @Upsert(entity = LocalFile::class)
    fun updateFileList(files: List<LocalFile>)

    @Insert(entity = LocalTag::class, onConflict = OnConflictStrategy.IGNORE)
    fun addTag(tag: LocalTag)

    @Insert(entity = FileTagRelation::class, onConflict = OnConflictStrategy.IGNORE)
    fun addFileTagRelation(relation: FileTagRelation)

    @Query("UPDATE LocalFile SET status = :status WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    fun updateFileStatus(connectionId: Int, parentPath: String, filename: String, status: LocalFileStatus)

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND type = :type AND status = :status LIMIT :limit")
    fun getUnprocessedTagFiles(connectionId: Int, limit: Int, type: FileType = FileType.TAG, status: LocalFileStatus = LocalFileStatus.UNPROCESSED): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND type = :type LIMIT :limit")
    fun getAllTagFiles(connectionId: Int, limit: Int, type: FileType = FileType.TAG): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND type != :tagFileType AND filename LIKE :filenamePattern LIMIT 1")
    fun getCorrespondingFileForTagFile(connectionId: Int, parentPath: String, tagFileType: FileType = FileType.TAG, filenamePattern: String): LocalFile?

    @Transaction
    fun updateFileTags(connectionId: Int, tagFile: LocalFile, tags: List<String>) {
        // Find corresponding tag file
        val filenamePattern = tagFile.filename.substringBeforeLast('.').plus(".%")
        val parentPath = tagFile.parentPath
        val correspondingFile = getCorrespondingFileForTagFile(connectionId, parentPath, filenamePattern = filenamePattern)

        // If cannot find, mark as REMOVED
        if (correspondingFile == null) {
            updateFileStatus(connectionId, parentPath, tagFile.filename, LocalFileStatus.REMOVED)
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
        updateFileStatus(connectionId, parentPath, tagFile.filename, LocalFileStatus.SUCCESS)
    }
}

@Entity(primaryKeys = [
    "connectionId",
    "parentPath",
    "filename",
])
data class LocalFile(
    val connectionId: Int,
    val parentPath: String,
    val filename: String,
    val type: FileType,
    @Deprecated("Using Utils.canHaveThumbnail instead") val canHaveThumbnail: Boolean,
//    val thumbnailPath: String?,
    val status: LocalFileStatus = LocalFileStatus.UNPROCESSED,
    val lastModified: Date,
)

@Entity
data class LocalTag(
    @PrimaryKey val tag: String,
)

@Entity
data class LocalTagWithMetadata(
    val tag: String,
    val count: Int,
)

@Entity(primaryKeys = [
    "connectionId",
    "parentPath",
    "filename",
    "tag",
])
data class FileTagRelation(
    val connectionId: Int,
    val parentPath: String,
    val filename: String,
    val tag: String
)

enum class FileType {
    FILE, FOLDER, TAG, CONNECTION, ADD_CONNECTION
}

enum class LocalFileStatus {
    UNPROCESSED, SUCCESS, ERROR, REMOVED
}
