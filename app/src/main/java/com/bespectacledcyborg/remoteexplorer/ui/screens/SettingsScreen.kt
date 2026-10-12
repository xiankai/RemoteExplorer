package com.bespectacledcyborg.remoteexplorer.ui.screens

import android.content.ClipData
import java.time.DateTimeException
import java.time.ZoneId
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import com.bespectacledcyborg.remoteexplorer.ui.components.appVersionLabel
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bespectacledcyborg.remoteexplorer.data.GridColumns
import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.data.db.ThemeMode
import com.bespectacledcyborg.remoteexplorer.ui.components.TextFieldDialog
import com.bespectacledcyborg.remoteexplorer.ui.components.columnCountLabel
import com.bespectacledcyborg.remoteexplorer.ui.components.columnCountOptions
import com.bespectacledcyborg.remoteexplorer.ui.components.columnsChosen
import com.bespectacledcyborg.remoteexplorer.ui.components.columnsLabel
import com.bespectacledcyborg.remoteexplorer.ui.components.screenWidthDp
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = viewModel(),
    onBackPressed: () -> Unit
) {
    val settings by settingsViewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box {
                var selectingTheme by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { selectingTheme = !selectingTheme },
                    content = { Text("Theme: ${settings.themeMode.label}") },
                )
                DropdownMenu(
                    expanded = selectingTheme,
                    onDismissRequest = { selectingTheme = false },
                ) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                selectingTheme = false
                                settingsViewModel.setThemeMode(mode)
                            },
                        )
                    }
                }
            }

            TextFieldDialog(
                title = "Max Storage Cache Size",
                value = settings.maxStorageCacheSize,
                onValueChange = { newSize ->
                    if (newSize.toInt() <= 0) {
                        throw Error("must be more than 0")
                    }
                    // TODO: throw error if newSize exceeds system size or is lower than current cache usage
                    settingsViewModel.setMaxStorageCacheSize(newSize.toInt())
                    newSize
                },
                display = { "$it GB" }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Box {
                var selectingVolume by remember { mutableStateOf(false) }
                // Read when the menu opens rather than once at composition: a card can be
                // inserted or removed while this screen is on top of it, and a picker
                // offering a volume that is no longer there is worse than one that is a
                // moment out of date.
                val volumes = remember(selectingVolume) {
                    settingsViewModel.thumbnailVolumes()
                }
                val chosen = volumes.firstOrNull { it.id == settings.thumbnailCacheVolume }

                TextButton(
                    onClick = { selectingVolume = !selectingVolume },
                    content = {
                        // A volume that has been removed still has a setting pointing at
                        // it, and saying so is more use than silently showing "Internal" —
                        // the cache really is falling back, and the user is the only one
                        // who can put the card back.
                        Text(
                            "Thumbnail Cache: " +
                                    (chosen?.label ?: "${settings.thumbnailCacheVolume} (not available)")
                        )
                    },
                )
                DropdownMenu(
                    expanded = selectingVolume,
                    onDismissRequest = { selectingVolume = false },
                ) {
                    volumes.forEach { volume ->
                        DropdownMenuItem(
                            text = { Text("${volume.label} — ${volume.freeBytes / (1024 * 1024 * 1024)} GB free") },
                            onClick = {
                                settingsViewModel.setThumbnailCacheVolume(volume.id)
                                selectingVolume = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Where a viewing day starts, and in which zone (#76). Side by side because
            // they only mean anything together. Both are read each time a view is recorded,
            // so a change applies from the next one, and days already counted stay put.
            Box {
                var selectingDayStart by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { selectingDayStart = !selectingDayStart },
                    content = {
                        Text("Viewing Day Starts At: ${clockTime(settings.viewingDayStartMinutes)}")
                    },
                )
                DropdownMenu(
                    expanded = selectingDayStart,
                    onDismissRequest = { selectingDayStart = false },
                ) {
                    (0 until 24).forEach { hour ->
                        DropdownMenuItem(
                            text = { Text(clockTime(hour * 60)) },
                            onClick = {
                                selectingDayStart = false
                                settingsViewModel.setViewingDayStart(hour * 60)
                            },
                        )
                    }
                }
            }

            TextFieldDialog(
                title = "Viewing Day Time Zone",
                value = settings.viewingDayZone,
                onValueChange = { input ->
                    val zone = input.trim()
                    try {
                        ZoneId.of(zone)
                    } catch (e: DateTimeException) {
                        throw IllegalArgumentException("not a time zone, e.g. Asia/Tokyo")
                    }
                    settingsViewModel.setViewingDayZone(zone)
                    zone
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Box {
                var selectingColumnCount by remember { mutableStateOf(false) }
                val widthDp = screenWidthDp()
                val columns = GridColumns(settings.columnTileWidthDp, settings.defaultColumnCount)
                TextButton(
                    onClick = { selectingColumnCount = !selectingColumnCount },
                    content = { Text("Column Count: ${columnsLabel(columns, widthDp)}") },
                )
                DropdownMenu(
                    expanded = selectingColumnCount,
                    onDismissRequest = { selectingColumnCount = false },
                ) {
                    columnCountOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(columnCountLabel(option)) },
                            onClick = {
                                selectingColumnCount = false
                                settingsViewModel.setColumns(columnsChosen(option, widthDp))
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box {
                var selectingSort by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { selectingSort = !selectingSort },
                    content = { Text("Default Sort: ${settings.defaultSort.label}") },
                )
                DropdownMenu(
                    expanded = selectingSort,
                    onDismissRequest = { selectingSort = false },
                ) {
                    SortType.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            onClick = {
                                selectingSort = false
                                settingsViewModel.setDefaultSort(sort)
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.showCaptions,
                        role = Role.Switch,
                        onValueChange = settingsViewModel::setShowCaptions,
                    )
                    .padding(horizontal = 12.dp),
            ) {
                Text("Show author captions", modifier = Modifier.weight(1f))
                Switch(checked = settings.showCaptions, onCheckedChange = null)
            }

            Spacer(modifier = Modifier.weight(1f))

            AppVersionRow()
        }
    }
}

@Composable
private fun AppVersionRow() {
    val version = remember { appVersionLabel() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Text(
        text = "Version $version",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Copy version") {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Remote Explorer version", version)))
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private fun clockTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
