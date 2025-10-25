package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.SettingsDao
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    suspend fun update(settings: SettingsEntity) {
        settingsDao.update(settings)
    }
}
