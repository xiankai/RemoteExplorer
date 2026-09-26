package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A local folder that is a copy of a remote one (#51): everything under
 * [localSubPath] on the local root [localConnectionId] is taken to be the file at the same
 * relative path under [remoteSubPath] on [remoteConnectionId].
 *
 * Sub-paths are relative to each connection's root and carry no leading or trailing slash;
 * an empty one is the root itself. Keyed by the local side, because a local folder is a
 * copy of exactly one thing — while one remote folder may be copied to more than one place.
 *
 * Both connections cascade: a pairing that outlives either side would send reads for a
 * connection id that no longer means anything, or, worse, one that has been reused.
 */
@Entity(
    primaryKeys = ["localConnectionId", "localSubPath"],
    foreignKeys = [
        ForeignKey(
            entity = Connection::class,
            parentColumns = ["id"],
            childColumns = ["localConnectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Connection::class,
            parentColumns = ["id"],
            childColumns = ["remoteConnectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("remoteConnectionId")],
)
data class Pairing(
    val localConnectionId: Int,
    val localSubPath: String,
    val remoteConnectionId: Int,
    val remoteSubPath: String,
)

@Dao
interface PairingDao {
    /** All of them: there are a handful at most, and every lookup wants the whole set. */
    @Query("SELECT * FROM Pairing")
    fun getPairings(): Flow<List<Pairing>>

    @Upsert
    suspend fun upsert(pairing: Pairing)

    @Delete
    suspend fun delete(pairing: Pairing)
}
