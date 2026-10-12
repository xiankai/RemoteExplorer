package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.ColumnInfo
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

    /**
     * Nullable, unlike [getSettings]: the default row is inserted on a background coroutine
     * at startup, so a collector can arrive before it exists.
     */
    @Query("SELECT * FROM settings LIMIT 1")
    fun observeSettings(): Flow<SettingsEntity?>

    /**
     * The column count alone. The grid's picker writes it as well as Settings, and a
     * whole-row copy taken from either screen's last-seen settings could put back a value
     * the other had just changed.
     */
    @Query("UPDATE settings SET defaultColumnCount = :count")
    suspend fun setDefaultColumnCount(count: Int)

    @Query("UPDATE settings SET showCaptions = :show")
    suspend fun setShowCaptions(show: Boolean)

    @Query("UPDATE settings SET coverShuffle = :shuffle")
    suspend fun setCoverShuffle(shuffle: CoverShuffle)

    @Update
    suspend fun update(settingsEntity: SettingsEntity)

    @Insert
    suspend fun insert(settings: SettingsEntity)
}

const val AUTO_COLUMN_COUNT = 0

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 0, // Singleton, always id = 0
    val maxStorageCacheSize: Int = 4, // Max cache size in GB
    val defaultColumnCount: Int = AUTO_COLUMN_COUNT,
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
    /**
     * Light, dark, or whatever the device is set to (#65). Light by default, the existing
     * row included: the owner asked for it, and the dark default was what read as murky.
     */
    @ColumnInfo(defaultValue = "LIGHT")
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    /**
     * When a viewing day starts, in minutes after local midnight (#76). Noon by default, so
     * a sitting that runs past midnight counts as one day rather than two. Read each time
     * a view is recorded rather than once at startup, so a change applies from the next
     * view on. Days already counted keep the day they were given.
     */
    @ColumnInfo(defaultValue = "$DEFAULT_VIEWING_DAY_START_MINUTES")
    val viewingDayStartMinutes: Int = DEFAULT_VIEWING_DAY_START_MINUTES,
    /**
     * The zone viewing days are counted in. Deliberately a setting and not the device's
     * current zone: the boundary belongs to the viewing habit, not to wherever the phone is.
     */
    @ColumnInfo(defaultValue = DEFAULT_VIEWING_DAY_ZONE)
    val viewingDayZone: String = DEFAULT_VIEWING_DAY_ZONE,
    @ColumnInfo(defaultValue = "0")
    val showCaptions: Boolean = false,
    @ColumnInfo(defaultValue = "MARKED")
    val coverShuffle: CoverShuffle = CoverShuffle.MARKED,
)

enum class CoverShuffle(val label: String) {
    OFF("Off"),
    MARKED("Only folders set to shuffle"),
    ALL("Shuffle all folders"),
}

const val DEFAULT_VIEWING_DAY_START_MINUTES = 12 * 60
const val DEFAULT_VIEWING_DAY_ZONE = "Asia/Tokyo"

enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("Follow system"),
}
