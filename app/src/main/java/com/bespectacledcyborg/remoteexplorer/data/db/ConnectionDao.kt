package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.util.Date

/** Standard SMB port. Overridable per connection so a share can sit behind a forwarded port. */
const val DEFAULT_SMB_PORT = 445

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM Connection")
    fun getConnections(): Flow<List<Connection>>

    @Upsert
    suspend fun addConnection(connection: Connection)

    @Delete
    suspend fun deleteConnection(connection: Connection)
}

@Entity(indices = [Index("name", unique = true)])
data class Connection(
    @PrimaryKey(autoGenerate = true) val id: Int,

    val name: String,
    val hostname: String,
    @ColumnInfo(defaultValue = "445") val port: Int = DEFAULT_SMB_PORT,
    val username: String,
    /** Encrypted at rest by CredentialConverters; plaintext only in memory. */
    val password: SecretText,
    val domain: String,
    val share: String,

    val lastModified: Date,
)