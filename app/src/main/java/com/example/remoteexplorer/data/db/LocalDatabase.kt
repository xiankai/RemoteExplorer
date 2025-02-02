package com.example.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import java.util.Date

@Dao
interface LocalDatabaseDao {
    @Query("SELECT LocalFile.* FROM LocalFile " +
            "LEFT JOIN FileTagRelation " +
            "ON LocalFile.filename = FileTagRelation.filename " +
            "WHERE FileTagRelation.tag = :tag")
    fun getFilesWithTag(tag: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getFiles(connectionId: Int, parentPath: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename LIKE :filter")
    fun getFilesWithFilter(connectionId: Int, parentPath: String, filter: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND canHaveThumbnail = TRUE LIMIT :limit")
    fun getFolderThumbnails(connectionId: Int, parentPath: String, limit: Int): List<LocalFile>

    @Query("SELECT * FROM LocalTag")
    fun getTags(): List<LocalTag>

    @Upsert(entity = LocalFile::class)
    fun updateFileList(files: List<LocalFile>)

    @Insert(entity = LocalTag::class, onConflict = OnConflictStrategy.IGNORE)
    fun addTag(tag: LocalTag)

    @Insert(entity = FileTagRelation::class, onConflict = OnConflictStrategy.IGNORE)
    fun addFileTagRelation(relation: FileTagRelation)

    @Query("UPDATE LocalFile SET status = :status WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    fun updateFileStatus(connectionId: Int, parentPath: String, filename: String, status: LocalFileStatus)

    @Transaction
    fun updateFileTags(connectionId: Int, parentPath: String, filename: String, tagFilename: String, tags: List<String>) {
        tags.map {
            addTag(LocalTag(it))
            addFileTagRelation(
                FileTagRelation(
                    connectionId,
                    parentPath,
                    filename,
                    it
                )
            )
        }
        updateFileStatus(connectionId, parentPath, tagFilename, LocalFileStatus.SUCCESS)
    }

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND type = :type AND status = :status")
    fun getUnprocessedTagFiles(connectionId: Int, type: FileType = FileType.TAG, status: LocalFileStatus = LocalFileStatus.UNPROCESSED): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND type = :type")
    fun getAllTagFiles(connectionId: Int, type: FileType = FileType.TAG): List<LocalFile>
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