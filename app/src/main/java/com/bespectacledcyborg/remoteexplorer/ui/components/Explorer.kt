package com.bespectacledcyborg.remoteexplorer.ui.components

import ListViewItem
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ViewType
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
    getImageUrl: GetImageUrl,
    items: List<Item<T>>,
    handleItemClick: (Item<T>) -> () -> Unit,
    handleLongItemClick: (Item<T>) -> () -> Unit,
    handleRefresh: () -> Unit,
    viewType: ViewType = ViewType.GRID,
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
        if (viewType == ViewType.LIST) {
            LazyColumn {
                items(items) { item -> ListViewItem(
                    item.name,
                    item.type,
                    item.thumbnails,
                    handleItemClick(item),
                    handleLongItemClick(item),
                    getImageUrl,
                ) }
            }
        } else if (viewType == ViewType.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount)
            ) {
                items(items) { item -> ExplorerItem(
                    item.name,
                    item.type,
                    item.thumbnails,
                    handleItemClick(item),
                    handleLongItemClick(item),
                    getImageUrl,
                ) }
            }
        }
    }
}