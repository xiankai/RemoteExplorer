package com.bespectacledcyborg.remoteexplorer.ui.screens.header

import ExpandableSearchBar
import SystemIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.SortType

@Composable
fun ExplorerButtons(
    explorerViewModel: ExplorerViewModel,
    onOpenTagsScreen: () -> Unit,
) {
    val connectionName by explorerViewModel.connectionNameState.collectAsState()
    val path by explorerViewModel.pathState.collectAsState()
    ExpandableSearchBar(
        currentContext = connectionName + path,
        onSearch = { explorerViewModel.search(it) },
    )

    var selectingColumnCount by remember { mutableStateOf(false) }
    val columnCount by explorerViewModel.columnCountState.collectAsState()
    val columnCountOptions = List(12) { it + 1 }
    Box {
        IconButton(
            onClick = { selectingColumnCount = !selectingColumnCount },
            content = {
                Icon(
                    Icons.Filled.GridOn,
                    contentDescription = "Column Count",
                )
            }
        )
        DropdownMenu(
            expanded = selectingColumnCount,
            onDismissRequest = { selectingColumnCount = false },
        ) {
            columnCountOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.toString()) },
                    onClick = {
                        explorerViewModel.setColumnCount(option)
                        selectingColumnCount = false
                    },
                    trailingIcon = {
                        if (option == columnCount) {
                            Icon(Icons.Filled.Check, contentDescription = "Current")
                        }
                    },
                )
            }
        }
    }

    // Every order the listing has, not a name-only toggle: the time orders were implemented
    // and unreachable (#75), and the view orders (#76) sort a folder by the sum of what is
    // directly inside it.
    val sort by explorerViewModel.sortState.collectAsState()
    SortMenu(
        current = sort,
        options = SortType.entries,
        onSelect = explorerViewModel::sort,
    )
    IconButton(onClick = onOpenTagsScreen, content = {
        Icon(
            Icons.Filled.Sell,
            contentDescription = "Tags",
        )
    })

    val selectedFilters by explorerViewModel.filterState.collectAsState()
    var selectingFilters by remember { mutableStateOf(false) }
    val filters = listOf(
        IconType.IMAGE, IconType.VIDEO, IconType.AUDIO, IconType.TEXT, IconType.TAG
    )
    Box {
        IconButton(onClick = { selectingFilters = !selectingFilters }, content = {
            Icon(
                Icons.Filled.FilterAlt,
                contentDescription = "Filter",
            )
        })
        DropdownMenu(
            expanded = selectingFilters,
            onDismissRequest = { selectingFilters = false },
        ) {
            val onCheckedChange: (
                iconType: IconType,
            ) -> () -> Unit = { iconType ->
                {
                    val newFilters = if (selectedFilters.contains(iconType)) {
                        selectedFilters.minus(iconType)
                    } else {
                        selectedFilters.plus(iconType)
                    }
                    explorerViewModel.filter(newFilters)
                }
            }

            filters.forEach { iconType ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(selectedFilters.contains(iconType), {
                                onCheckedChange(iconType)()
                            })
                            SystemIcon(iconType)
                            Text(iconType.name)
                        }
                    },
                    onClick = onCheckedChange(iconType),
                )
            }
        }
    }
}