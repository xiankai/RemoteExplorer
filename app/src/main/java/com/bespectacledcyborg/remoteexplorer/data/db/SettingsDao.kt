package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.bespectacledcyborg.remoteexplorer.data.SortType
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings LIMIT 1")
    fun getSettings(): Flow<SettingsEntity>

    @Query("SELECT * FROM settings LIMIT 1")
    suspend fun getSettingsOnce(): SettingsEntity?

    @Query("SELECT * FROM settings LIMIT 1")
    fun observeSettings(): Flow<SettingsEntity?>

    @Query("UPDATE settings SET defaultColumnCount = :count, columnTileWidthDp = :tileWidthDp")
    suspend fun setColumns(count: Int, tileWidthDp: Int?)  // stack-reviewer-ignore: writing both together is the point; the only callers go through columnsChosen, which sets a tile width for a count and clears both for Auto, so no caller writes a bare count

    @Query("UPDATE settings SET defaultSort = :sort")
    suspend fun setDefaultSort(sort: SortType)

    @Query("UPDATE settings SET showCaptions = :show")
    suspend fun setShowCaptions(show: Boolean)

    @Query("UPDATE settings SET showHiddenFiles = :show")
    suspend fun setShowHiddenFiles(show: Boolean)

    @Query("UPDATE settings SET coverShuffle = :shuffle")
    suspend fun setCoverShuffle(shuffle: CoverShuffle)

    @Query("UPDATE settings SET firstPageFolders = :folders")
    suspend fun setFirstPageFolders(folders: String)

    @Update
    suspend fun update(settingsEntity: SettingsEntity)

    @Insert
    suspend fun insert(settings: SettingsEntity)
}

const val AUTO_COLUMN_COUNT = 0

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 0,
    val maxStorageCacheSize: Int = 4,
    val defaultColumnCount: Int = AUTO_COLUMN_COUNT,
    val thumbnailCacheVolume: String? = null,
    @ColumnInfo(defaultValue = "LIGHT")
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    @ColumnInfo(defaultValue = "$DEFAULT_VIEWING_DAY_START_MINUTES")
    val viewingDayStartMinutes: Int = DEFAULT_VIEWING_DAY_START_MINUTES,
    @ColumnInfo(defaultValue = DEFAULT_VIEWING_DAY_ZONE)
    val viewingDayZone: String = DEFAULT_VIEWING_DAY_ZONE,
    @ColumnInfo(defaultValue = "0")
    val showCaptions: Boolean = false,
    @ColumnInfo(defaultValue = "MARKED")
    val coverShuffle: CoverShuffle = CoverShuffle.MARKED,
    val columnTileWidthDp: Int? = null,
    @ColumnInfo(defaultValue = "NAME_ASC")
    val defaultSort: SortType = SortType.NAME_ASC,
    @ColumnInfo(defaultValue = "0")
    val showHiddenFiles: Boolean = false,
    @ColumnInfo(defaultValue = "")
    val firstPageFolders: String = "",
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
