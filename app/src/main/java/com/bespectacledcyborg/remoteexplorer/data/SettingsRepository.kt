package com.bespectacledcyborg.remoteexplorer.data

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

    /** The grid's column count as stored, following every change from either screen. */
    fun columnCount(): Flow<Int> =
        settingsDao.observeSettings()
            .filterNotNull()
            .map { it.defaultColumnCount }
            .distinctUntilChanged()

    suspend fun setColumnCount(count: Int) {
        settingsDao.setDefaultColumnCount(count)
    }

    fun showCaptions(): Flow<Boolean> =
        settingsDao.observeSettings()
            .filterNotNull()
            .map { it.showCaptions }
            .distinctUntilChanged()

    suspend fun setShowCaptions(show: Boolean) {
        settingsDao.setShowCaptions(show)
    }

    suspend fun update(settings: SettingsEntity) {
        settingsDao.update(settings)
    }
}
