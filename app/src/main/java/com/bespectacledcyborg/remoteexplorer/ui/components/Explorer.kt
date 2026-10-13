package com.bespectacledcyborg.remoteexplorer.ui.components

import ListViewItem
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ViewType

data class Item<T> (
    val name: String,
    val type: FileType,
    val thumbnails: List<String>,
    val item: T,
    val captioned: Boolean = false,
    val cover: CoverMode = CoverMode.DEFAULT,
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
    isRefreshing: Boolean = false,
    onVisibleItemsChanged: (List<Item<T>>) -> Unit = {},
    scrollKey: Any? = null,
) {
    val gridState = key(scrollKey) { rememberLazyGridState() }
    val listState = key(scrollKey) { rememberLazyListState() }
    val currentItems by rememberUpdatedState(items)
    val currentOnVisible by rememberUpdatedState(onVisibleItemsChanged)

    LaunchedEffect(viewType, gridState, listState) {
        snapshotFlow {
            val visible = if (viewType == ViewType.LIST) {
                listState.layoutInfo.visibleItemsInfo.map { it.index }
            } else {
                gridState.layoutInfo.visibleItemsInfo.map { it.index }
            }
            visible to currentItems
        }
            .distinctUntilChanged()
            .collect { (visible, all) ->
                if (visible.isEmpty() || all.isEmpty()) return@collect
                currentOnVisible(inViewportOrder(visible, all))
            }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = handleRefresh,
    ) {
        if (viewType == ViewType.LIST) {
            LazyColumn(state = listState) {
                items(items) { item -> ListViewItem(
                    item.name,
                    item.type,
                    item.thumbnails,
                    handleItemClick(item),
                    handleLongItemClick(item),
                    getImageUrl,
                    item.cover,
                ) }
            }
        } else if (viewType == ViewType.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                state = gridState,
            ) {
                items(items) { item ->
                    key(columnCount) {  // stack-reviewer-ignore: this is Compose's runtime key() inside one item's own lambda, not a LazyList item key; it only has to be unique among that item's children, and it is the sole child. Its job is to remount the tile when the column count changes so images are requested at the new size.
                        ExplorerItem(
                            item.name,
                            item.type,
                            item.thumbnails,
                            handleItemClick(item),
                            handleLongItemClick(item),
                            getImageUrl,
                            item.captioned,
                            item.cover,
                        )
                    }
                }
            }
        }
    }
}

/**
 * [visible] first, in order, then a screen's worth below it, then half a screen above.
 *
 * Below before above because scrolling down is how a listing is read; the half screen above
 * covers flicking back up. Both are bounded by the size of the screen rather than a fixed
 * count, so a grid of eight columns looks as far ahead as one of two.
 */
internal fun <T> inViewportOrder(visible: List<Int>, all: List<T>): List<T> {
    val first = visible.min()
    val last = visible.max()
    val span = last - first + 1
    val ahead = (last + 1..minOf(all.lastIndex, last + span))
    val behind = (maxOf(0, first - span / 2) until first).reversed()
    return (visible.sorted() + ahead + behind)
        .filter { it in all.indices }
        .distinct()
        .map { all[it] }
}
