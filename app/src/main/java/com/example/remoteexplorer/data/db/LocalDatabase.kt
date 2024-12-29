package com.example.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.Date

@Dao
interface LocalDatabaseDao {
    @Query("SELECT * FROM LocalFile " +
            "LEFT JOIN FileTagRelation " +
            "ON LocalFile.filename = FileTagRelation.filename " +
            "WHERE FileTagRelation.tag = :tag")
    fun getFilesWithTag(tag: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile")
    fun getFiles(): List<LocalFile>

    @Query("SELECT * FROM LocalTag")
    fun getTags(): List<LocalTag>
}

@Entity
data class LocalFile(
    @PrimaryKey val filename: String,

    val remotePath: String,
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