package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bespectacledcyborg.remoteexplorer.data.GridColumns
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.data.db.ThemeMode
import com.bespectacledcyborg.remoteexplorer.ui.theme.applyThemeMode
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailVolume
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.availableThumbnailVolumes
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.discardThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun setColumns(columns: GridColumns) {
        viewModelScope.launch { settingsRepository.setColumns(columns) }
    }

    fun setDefaultSort(sort: SortType) {
        viewModelScope.launch { settingsRepository.setDefaultSort(sort) }
    }

    fun setShowCaptions(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowCaptions(show) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            // Applied whether or not the write lands, so the screen never shows one theme
            // while the menu says another; a failed write only means the next launch puts
            // the stored one back.
            try {
                settingsRepository.update(settings.value.copy(themeMode = mode))
            } finally {
                applyThemeMode(getApplication(), mode)
            }
        }
    }

    /** Where a viewing day starts, in minutes after local midnight (#76). */
    fun setViewingDayStart(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.update(settings.value.copy(viewingDayStartMinutes = minutes))
        }
    }

    /** The zone viewing days are counted in. [zone] must already be a valid zone id. */
    fun setViewingDayZone(zone: String) {
        viewModelScope.launch {
            settingsRepository.update(settings.value.copy(viewingDayZone = zone))
        }
    }

    /** Volumes the thumbnail cache can be put on, for the picker to offer. */
    fun thumbnailVolumes(): List<ThumbnailVolume> =
        availableThumbnailVolumes(getApplication())

    /**
     * Repoints the thumbnail cache, discarding whatever is on the volume it leaves.
     *
     * The old cache goes rather than moving with it. Thumbnails being regenerable is the
     * reason they are the safe thing to put on removable storage in the first place, so
     * copying them across buys back only the re-render of whatever is on screen — while
     * leaving them behind would turn a cache into litter on a volume nothing points at.
     */
    fun setThumbnailCacheVolume(volumeId: String?) {
        viewModelScope.launch {
            val previous = settings.value.thumbnailCacheVolume
            if (previous == volumeId) return@launch

            settingsRepository.update(
                settings.value.copy(thumbnailCacheVolume = volumeId)
            )
            withContext(Dispatchers.IO) {
                discardThumbnailCache(getApplication(), previous)
            }
        }
    }
}