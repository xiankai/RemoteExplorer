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
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Item<T> (
    val name: String,
    val type: FileType,
    val thumbnails: List<String>,
    val item: T,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T>Explorer(
    explorerViewModel: ExplorerViewModel,
    items: List<Item<T>>,
    handleItemClick: (Item<T>) -> () -> Unit,
    handleLongItemClick: (Item<T>) -> () -> Unit,
    handleRefresh: () -> Unit,
    columnCount: Int,
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
            columns = GridCells.Fixed(columnCount)
        ) {
            items(items) { item ->
                ExplorerItem(
                    explorerViewModel,
                    item.name,
                    item.type,
                    item.thumbnails,
                    handleItemClick(item),
                    handleLongItemClick(item),
                )
            }
        }
    }
}