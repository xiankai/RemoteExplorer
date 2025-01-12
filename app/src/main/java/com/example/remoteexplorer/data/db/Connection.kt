package com.example.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM Connection")
    fun getConnections(): Flow<List<Connection>>

    @Upsert
    suspend fun addConnection(connection: Connection)

    @Delete
    suspend fun deleteConnection(connection: Connection)
}

@Entity
data class Connection(
    @PrimaryKey val name: String,

    val hostname: String,
    val username: String,
    val password: String,
    val domain: String,
    val share: String,

    val lastModified: Date,
)