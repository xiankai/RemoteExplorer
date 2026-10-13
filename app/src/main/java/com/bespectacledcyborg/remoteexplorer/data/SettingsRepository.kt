package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.AUTO_COLUMN_COUNT
import com.bespectacledcyborg.remoteexplorer.data.db.CoverShuffle
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsDao
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class GridColumns(
    val tileWidthDp: Int?,
    val legacyCount: Int,
) {
    val isAuto: Boolean get() = tileWidthDp == null && legacyCount == AUTO_COLUMN_COUNT

    companion object {
        val AUTO = GridColumns(tileWidthDp = null, legacyCount = AUTO_COLUMN_COUNT)
    }
}

class SettingsRepository(
    private val settingsDao: SettingsDao
) {
    fun getSettingsAsStateFlow(scope: CoroutineScope): StateFlow<SettingsEntity> =
        settingsDao.getSettings()
            .map { it }
            .stateIn(
                scope,
                started = SharingStarted.Lazily,
                initialValue = SettingsEntity()
            )

    /** One-shot read, for callers that need a value before the flow has emitted. */
    suspend fun getSettingsOnce(): SettingsEntity? = settingsDao.getSettingsOnce()

    fun columns(): Flow<GridColumns> =
        settingsDao.observeSettings()
            .filterNotNull()
            .map { GridColumns(it.columnTileWidthDp, it.defaultColumnCount) }
            .distinctUntilChanged()

    suspend fun setColumns(columns: GridColumns) {
        settingsDao.setColumns(columns.legacyCount, columns.tileWidthDp)
    }

    fun defaultSort(): Flow<SortType> =
        settingsDao.observeSettings()
            .filterNotNull()
            .map { it.defaultSort }
            .distinctUntilChanged()

    suspend fun setDefaultSort(sort: SortType) {
        settingsDao.setDefaultSort(sort)
    }

    fun showCaptions(): Flow<Boolean> =
        settingsDao.observeSettings()
            .filterNotNull()
            .map { it.showCaptions }
            .distinctUntilChanged()

    suspend fun setShowCaptions(show: Boolean) {
        settingsDao.setShowCaptions(show)
    }

    fun coverShuffle(): Flow<CoverShuffle> =
        settingsDao.observeSettings()
            .filterNotNull()
            .map { it.coverShuffle }
            .distinctUntilChanged()

    suspend fun setCoverShuffle(shuffle: CoverShuffle) {
        settingsDao.setCoverShuffle(shuffle)
    }

    suspend fun update(settings: SettingsEntity) {
        settingsDao.update(settings)
    }
}
