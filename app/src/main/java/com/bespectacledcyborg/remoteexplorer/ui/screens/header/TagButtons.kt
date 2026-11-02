package com.bespectacledcyborg.remoteexplorer.ui.screens.header

import ExpandableSearchBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ViewType

@Composable
fun TagButtons(
    explorerViewModel: ExplorerViewModel
) {
    val sortState = explorerViewModel.tagSortState.collectAsState()
    val tagQuery = explorerViewModel.tagQueryState.collectAsState()

    // This button used to call search(""), the file listing's query, so it did nothing here.
    ExpandableSearchBar(
        currentContext = "tags",
        onSearch = { explorerViewModel.searchTags(it) },
        searchAsYouType = true,
        initialValue = tagQuery.value,
    )
    IconButton(
        onClick = { explorerViewModel.setTagScreenViewType(ViewType.LIST) },
        content = {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = "List View",
            )
        }
    )
    IconButton(
        onClick = { explorerViewModel.setTagScreenViewType(ViewType.GRID) },
        content = {
            Icon(
                Icons.Filled.GridOn,
                contentDescription = "Grid View",
            )
        }
    )
    // Name both ways, and every view order (#76): a tag sums the files it lists or
    // contains. The time orders stay off, since a tag has no modified time to sort by.
    SortMenu(
        current = sortState.value,
        options = SortType.entries.filter {
            it != SortType.LAST_MODIFIED_ASC && it != SortType.LAST_MODIFIED_DESC
        },
        onSelect = explorerViewModel::sortTags,
    )
}