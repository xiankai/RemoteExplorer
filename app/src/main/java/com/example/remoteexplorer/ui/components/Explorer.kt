package com.example.remoteexplorer.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.remoteexplorer.data.db.FileType

data class Item<T> (
    val name: String,
    val type: FileType,
    val urls: List<String>,
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
                type = item.type,
                urls = item.urls,
                handleItemClick = handleItemClick(item),
                handleLongItemClick = handleLongItemClick(item),
            )
        }
    }
}