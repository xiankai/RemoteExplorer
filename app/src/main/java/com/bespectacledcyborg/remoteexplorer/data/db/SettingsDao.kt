package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings LIMIT 1")
    fun getSettings(): Flow<SettingsEntity>

    @Query("SELECT * FROM settings LIMIT 1")
    suspend fun getSettingsOnce(): SettingsEntity?

    @Update
    suspend fun update(settingsEntity: SettingsEntity)

    @Insert
    suspend fun insert(settings: SettingsEntity)
}

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 0, // Singleton, always id = 0
    val maxStorageCacheSize: Int = 4, // Max cache size in GB
    val defaultColumnCount: Int = 3,    // Default column count
    /**
     * Which volume the thumbnail cache lives on — null for internal storage, otherwise a
     * volume UUID (see `ThumbnailCacheLocation`).
     *
     * The UUID and not the path: where a card mounts is not stable, and a stored path that
     * has stopped existing cannot be told apart from a card that was taken out. Internal
     * stays the default because a card is usually the slower of the two, and this setting
     * buys capacity rather than speed.
     */
    val thumbnailCacheVolume: String? = null,
)
