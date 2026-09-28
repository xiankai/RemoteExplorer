package com.bespectacledcyborg.remoteexplorer.ui.theme

import android.app.UiModeManager
import android.content.Context
import com.bespectacledcyborg.remoteexplorer.data.db.ThemeMode

/**
 * Makes [mode] the app's own night mode, over whatever the device is set to (#65).
 *
 * Through the platform's per-app night mode rather than only choosing a colour scheme in
 * Compose, so that everything reading the configuration agrees: `isSystemInDarkTheme`,
 * `-night` resources such as the file-type icons, and the system's own windows. The
 * platform keeps the choice across restarts on its own; the setting in Room is what the
 * Settings screen shows, and what is put back here on each launch.
 *
 * A call that changes nothing changes no configuration, so it is safe on every launch.
 */
fun applyThemeMode(context: Context, mode: ThemeMode) {
    val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return
    uiModeManager.setApplicationNightMode(
        when (mode) {
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
    )
}
