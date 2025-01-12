package com.example.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import java.util.Date

@Dao
interface LocalDatabaseDao {
    @Query("SELECT LocalFile.* FROM LocalFile " +
            "LEFT JOIN FileTagRelation " +
            "ON LocalFile.filename = FileTagRelation.filename " +
            "WHERE FileTagRelation.tag = :tag")
    fun getFilesWithTag(tag: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE parentPath = :parentPath")
    fun getFiles(parentPath: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE parentPath = :parentPath AND filename LIKE :filter")
    fun getFilesWithFilter(parentPath: String, filter: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE parentPath = :parentPath AND canHaveThumbnail = TRUE LIMIT :limit")
    fun getFolderThumbnails(parentPath: String, limit: Int): List<LocalFile>

    @Query("SELECT * FROM LocalTag")
    fun getTags(): List<LocalTag>

    @Upsert(entity = LocalFile::class)
    fun updateFileList(files: List<LocalFile>)
}

@Entity
data class LocalFile(
    @PrimaryKey val filename: String,

    val parentPath: String,
    val type: FileType,
    val canHaveThumbnail: Boolean,
    val lastModified: Date,
)

@Entity
data class LocalTag(
    @PrimaryKey val tag: Int,
)

@Entity(primaryKeys = ["filename", "tag"])
data class FileTagRelation(
    val filename: String,
    val tag: String
)

enum class FileType {
    FILE, FOLDER, CONNECTION, ADD_CONNECTION
}