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

    /** Null for a connection that no longer exists. */
    @Query("SELECT * FROM Connection WHERE id = :id")
    suspend fun getConnection(id: Int): Connection?

    /** Null for a connection that no longer exists. */
    @Query("SELECT type FROM Connection WHERE id = :id")
    suspend fun getConnectionType(id: Int): ConnectionType?

    @Upsert
    suspend fun addConnection(connection: Connection)

    @Delete
    suspend fun deleteConnection(connection: Connection)
}

/** What a [Connection] points at. */
enum class ConnectionType {
    /** A share on a file server. */
    SMB,

    /** A folder on the device's own storage — a Syncthing'd copy of a share, say (#51). */
    LOCAL,
}

/**
 * A saved place to browse.
 *
 * One entity for both kinds, told apart by [type], so that a local root gets a
 * `connectionId` like a share does and every table keyed by connection — the listing
 * cache, tags, thumbnails — works for it unchanged. The alternative, a separate entity,
 * would have been a second identity for every one of those tables to carry.
 *
 * The SMB fields are nullable since 14, because a local root has none of them: NULL says
 * "not applicable" where an empty string would read as a share with a blank hostname.
 * [localRoot] is the reverse, set for [ConnectionType.LOCAL] alone.
 */
@Entity(indices = [Index("name", unique = true)])
data class Connection(
    @PrimaryKey(autoGenerate = true) val id: Int,

    val name: String,
    @ColumnInfo(defaultValue = "SMB") val type: ConnectionType = ConnectionType.SMB,
    val hostname: String?,
    @ColumnInfo(defaultValue = "445") val port: Int = DEFAULT_SMB_PORT,
    val username: String?,
    /** Encrypted at rest by CredentialConverters; plaintext only in memory. */
    val password: SecretText?,
    val domain: String?,
    val share: String?,

    /** An absolute path on the device, for [ConnectionType.LOCAL]. */
    val localRoot: String? = null,

    val lastModified: Date,
)