package com.example.remoteexplorer.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.example.remoteexplorer.data.db.FileType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Item<T> (
    val name: String,
    val type: FileType,
    val urls: List<String>,
    val item: T,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T>Explorer(
    items: List<Item<T>>,
    handleItemClick: (Item<T>) -> () -> Unit,
    handleLongItemClick: (Item<T>) -> () -> Unit,
    handleRefresh: () -> Unit
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            handleRefresh()
            coroutineScope.launch {
                delay(1000)
                isRefreshing = false
            }
        },
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
}