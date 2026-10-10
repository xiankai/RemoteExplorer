package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM AnnotationIngest WHERE connectionId = :connectionId")
    fun getIngest(connectionId: Int): AnnotationIngest?

    @Upsert
    fun upsertIngest(ingest: AnnotationIngest)

    @Upsert
    fun upsertAnnotation(annotation: DirAnnotation)

    @Query("DELETE FROM DirAnnotation WHERE connectionId = :connectionId AND parentPath = :parentPath AND id = :id")
    fun deleteAnnotation(connectionId: Int, parentPath: String, id: String)

    @Query("DELETE FROM DirAnnotation WHERE connectionId = :connectionId")
    fun clearAnnotations(connectionId: Int)

    @Query("SELECT * FROM DirAnnotation WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getAnnotations(connectionId: Int, parentPath: String): List<DirAnnotation>

    @Transaction
    fun apply(
        connectionId: Int,
        replace: Boolean,
        updates: List<AnnotationUpdate>,
        generation: String,
        bytesRead: Long,
    ) {
        if (replace) clearAnnotations(connectionId)
        updates.forEach { update ->
            val text = update.text
            if (text == null) {
                deleteAnnotation(connectionId, update.parentPath, update.id)
            } else {
                upsertAnnotation(
                    DirAnnotation(
                        connectionId = connectionId,
                        parentPath = update.parentPath,
                        id = update.id,
                        text = text,
                        language = update.language,
                        derivedFrom = update.derivedFrom,
                        supersedes = update.supersedes,
                    )
                )
            }
        }
        upsertIngest(AnnotationIngest(connectionId, generation, bytesRead))
    }
}

data class AnnotationUpdate(
    val parentPath: String,
    val id: String,
    val text: String?,
    val language: String?,
    val derivedFrom: String?,
    val supersedes: String?,
)

@Entity(primaryKeys = ["connectionId", "parentPath", "id"])
data class DirAnnotation(
    val connectionId: Int,
    val parentPath: String,
    val id: String,
    val text: String,
    val language: String?,
    val derivedFrom: String?,
    val supersedes: String?,
)

@Entity(primaryKeys = ["connectionId"])
data class AnnotationIngest(
    val connectionId: Int,
    val generation: String,
    val bytesRead: Long,
)
