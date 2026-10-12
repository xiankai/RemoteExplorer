package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert
import com.bespectacledcyborg.remoteexplorer.data.SortType
import kotlinx.coroutines.flow.Flow

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
data class FolderSort(
    val connectionId: Int,
    val path: String,
    val sort: SortType,
)

@Dao
interface FolderSortDao {
    @Query("SELECT * FROM FolderSort")
    fun observeAll(): Flow<List<FolderSort>>

    @Upsert
    suspend fun upsert(folderSort: FolderSort)

    @Query("DELETE FROM FolderSort WHERE connectionId = :connectionId AND path = :path")
    suspend fun delete(connectionId: Int, path: String)
}
