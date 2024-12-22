package com.example.greetingcard.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState

@Composable
fun HeaderButtons(
    listViewModel: ListViewModel
) {
    val sortState = listViewModel.sortState.collectAsState()

    IconButton(
        onClick = { listViewModel.search("") },
        content = {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search",
            )
        }
    )
    IconButton(
        onClick = { listViewModel.sort(
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