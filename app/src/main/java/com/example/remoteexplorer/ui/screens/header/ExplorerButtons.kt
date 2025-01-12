package com.example.remoteexplorer.ui.screens.header

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.example.remoteexplorer.ui.viewmodels.SortType

@Composable
fun ExplorerButtons(
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
    IconButton(
        onClick = {  },
        content = {
            Icon(
                Icons.Filled.Sell,
                contentDescription = "Tags",
            )
        }
    )
}