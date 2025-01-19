package com.example.remoteexplorer.data.db

import androidx.room.Dao
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

    @Transaction
    fun updateFileTags(file: LocalFile, tags: List<String>) {
        val connectionId = file.connectionId
        val parentPath = file.parentPath
        val filename = file.filename
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
    val canHaveThumbnail: Boolean,
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
    FILE, FOLDER, CONNECTION, ADD_CONNECTION
}