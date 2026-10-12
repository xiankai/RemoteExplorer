package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert

enum class CoverMode(val label: String) {
    DEFAULT("Default"),
    NEWEST("Newest"),
    SHUFFLE("Shuffle"),
    SHUFFLE_EVERY_VISIT("Shuffle every visit"),
}

@Entity(
    primaryKeys = ["connectionId", "path"],
    foreignKeys = [
        ForeignKey(
            entity = Connection::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FolderCoverChoice(
    val connectionId: Int,
    val path: String,
    val mode: CoverMode,
    val seed: Long,
)

@Dao
interface FolderCoverChoiceDao {
    @Query("SELECT * FROM FolderCoverChoice WHERE connectionId = :connectionId AND path = :path")
    suspend fun get(connectionId: Int, path: String): FolderCoverChoice?

    @Upsert
    suspend fun set(choice: FolderCoverChoice)

    @Query("DELETE FROM FolderCoverChoice WHERE connectionId = :connectionId AND path = :path")
    suspend fun clear(connectionId: Int, path: String)
}
