package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import kotlinx.coroutines.launch

typealias StorageSize = Number

class SettingsViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {
    val settings = settingsRepository.getSettingsAsStateFlow(viewModelScope)

    fun setMaxStorageCacheSize(size: Int) {
        viewModelScope.launch {
            settingsRepository.update(
                settings.value.copy(maxStorageCacheSize = size)
            )
        }
    }

    fun setDefaultColumnCount(count: Int) {
        viewModelScope.launch {
            settingsRepository.update(
                settings.value.copy(defaultColumnCount = count)
            )
        }
    }
}