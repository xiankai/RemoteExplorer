package com.example.remoteexplorer.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.remoteexplorer.data.db.Connection

data class Item<T> (
    val name: String,
    val type: String,
    val url: String,
    val item: T,
)

@Composable
fun <T>Explorer(
    items: List<Item<T>>,
    handleItemClick: (Item<T>) -> () -> Unit,
    handleLongItemClick: (Item<T>) -> () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
    ) {
        items(items) { item ->
            ExplorerItem(
                name = item.name,
                url = item.url,
                type = item.type,
                handleItemClick = handleItemClick(item),
                handleLongItemClick = handleLongItemClick(item),
            )
        }
    }
}