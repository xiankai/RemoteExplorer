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
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ViewType

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
    // The spinner used to be driven by a local flag cleared after a hardcoded one-second
    // delay, so it reported a duration rather than a refresh: it stopped while a slow share
    // was still being listed, and span for a second over a screen that refreshes nothing.
    // The caller owns it now, and a screen with nothing to refresh passes false.
    isRefreshing: Boolean = false,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = handleRefresh,
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