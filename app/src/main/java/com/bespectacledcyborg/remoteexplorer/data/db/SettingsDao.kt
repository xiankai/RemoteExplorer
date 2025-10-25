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
    val defaultColumnCount: Int = 3     // Default column count
)
