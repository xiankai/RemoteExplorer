package com.example.remoteexplorer.ui.screens.header

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.example.remoteexplorer.ui.viewmodels.SortType

@Composable
fun ExplorerButtons(
    explorerViewModel: ExplorerViewModel,
    onOpenTagsScreen: () -> Unit,
) {
    IconButton(
        onClick = { explorerViewModel.search("") },
        content = {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search",
            )
        }
    )

    var selectingColumnCount by remember { mutableStateOf(false) }
    val columnCountOptions = List(12) { it + 1 }
    Box{
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
                    onClick = { explorerViewModel.setColumnCount(option) },
                )
            }
        }
    }

    val sortState = explorerViewModel.sortState.collectAsState()
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
        onClick = onOpenTagsScreen,
        content = {
            Icon(
                Icons.Filled.Sell,
                contentDescription = "Tags",
            )
        }
    )
}