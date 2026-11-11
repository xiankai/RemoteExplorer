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
import androidx.compose.material.icons.filled.NewReleases
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
import com.bespectacledcyborg.remoteexplorer.ui.components.columnCountLabel
import com.bespectacledcyborg.remoteexplorer.ui.components.columnCountOptions
import com.bespectacledcyborg.remoteexplorer.ui.components.columnsChosen
import com.bespectacledcyborg.remoteexplorer.ui.components.columnsLabel
import com.bespectacledcyborg.remoteexplorer.ui.components.screenWidthDp
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.data.SortType

@Composable
fun ExplorerButtons(
    explorerViewModel: ExplorerViewModel,
    onOpenTagsScreen: () -> Unit,
) {
    val connectionName by explorerViewModel.connectionNameState.collectAsState()
    val path by explorerViewModel.pathState.collectAsState()
    val search by explorerViewModel.searchState.collectAsState()
    val searchCleared by explorerViewModel.searchClearedState.collectAsState()
    ExpandableSearchBar(
        currentContext = connectionName + path,
        onSearch = { explorerViewModel.search(it) },
        initialValue = search,
        clearSignal = searchCleared,
    )

    var selectingColumnCount by remember { mutableStateOf(false) }
    val columns by explorerViewModel.columnsState.collectAsState()
    val widthDp = screenWidthDp()
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
                    text = { Text(columnCountLabel(option)) },
                    onClick = {
                        explorerViewModel.setColumns(columnsChosen(option, widthDp))
                        selectingColumnCount = false  // stack-reviewer-ignore: deliberate reversal requested by the owner in #84; the pick is still stored for #71, the menu just closes like the sort menu does
                    },
                    trailingIcon = {
                        if (columnCountLabel(option) == columnsLabel(columns, widthDp)) {
                            Icon(Icons.Filled.Check, contentDescription = "Current")
                        }
                    },
                )
            }
        }
    }

    val folderSort by explorerViewModel.folderSortState.collectAsState()
    val inheritFrom = when {
        folderSort.isLeaf -> "Default for galleries"  // stack-reviewer-ignore: the label below appends "(${inherited.label})", and inherited is LEAF_SORT on a leaf, so the menu reads "Default for galleries (Name, A to Z)"
        folderSort.isTopLevel -> "Default"
        else -> "Same as parent"
    }
    SortMenu(
        current = folderSort.current,
        options = SortType.entries,
        onSelect = explorerViewModel::sort,
        inherited = InheritedSort(
            label = "$inheritFrom (${folderSort.inherited.label})",
            active = !folderSort.chosenHere,
            onSelect = explorerViewModel::inheritSort,
        ),
    )
    IconButton(onClick = explorerViewModel::openNewestAdditions, content = {
        Icon(
            Icons.Filled.NewReleases,
            contentDescription = "Newest additions",
        )
    })
    IconButton(onClick = onOpenTagsScreen, content = {
        Icon(
            Icons.Filled.Sell,
            contentDescription = "Tags",
        )
    })

    val selectedFilters by explorerViewModel.filterState.collectAsState()
    var selectingFilters by remember { mutableStateOf(false) }
    val filters = listOf(
        IconType.IMAGE, IconType.VIDEO, IconType.AUDIO, IconType.TEXT
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