package com.example.remoteexplorer.ui.screens.header

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.example.remoteexplorer.ui.viewmodels.SortType
import com.example.remoteexplorer.ui.viewmodels.ViewType

@Composable
fun TagButtons(
    explorerViewModel: ExplorerViewModel
) {
    val sortState = explorerViewModel.sortState.collectAsState()

    IconButton(
        onClick = { explorerViewModel.search("") },
        content = {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search",
            )
        }
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
    IconButton(
        onClick = { explorerViewModel.sort(
            if (sortState.value == SortType.NAME_ASC) SortType.NAME_DESC else SortType.NAME_ASC
        ) },
        content = {
            Icon(
                Icons.Filled.SortByAlpha,
                contentDescription = "Sort by name",
            )
        }
    )
}