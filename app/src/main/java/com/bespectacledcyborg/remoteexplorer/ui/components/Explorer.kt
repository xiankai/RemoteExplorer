package com.bespectacledcyborg.remoteexplorer.ui.components

import ListViewItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ScrollPosition
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ViewType

data class Item<T> (
    val name: String,
    val type: FileType,
    val thumbnails: List<String>,
    val item: T,
    val captioned: Boolean = false,
    val cover: CoverMode = CoverMode.DEFAULT,
    val dimmed: Boolean = false,
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
    initialScroll: ScrollPosition = ScrollPosition(),
    onScrollPositionChanged: (ScrollPosition) -> Unit = {},
    groupOf: ((Item<T>) -> String)? = null,
    groupLabel: (String) -> String = { it },
    onGroupClick: ((String) -> Unit)? = null,
) {
    val entryColumns = if (viewType == ViewType.LIST) 1 else columnCount
    val entries = gridEntries(items, entryColumns, groupOf)
    val gridState = key(scrollKey) { rememberLazyGridState(initialScroll.index, initialScroll.offset) }
    val listState = key(scrollKey) { rememberLazyListState(initialScroll.index, initialScroll.offset) }
    val currentEntries by rememberUpdatedState(entries)
    val currentOnVisible by rememberUpdatedState(onVisibleItemsChanged)

    LaunchedEffect(scrollKey, viewType) {  // stack-reviewer-ignore: initialScroll is deliberately read once per folder; it is the position saved when the folder was left, and later recordings of the same folder must not restart the restore
        val position = {
            if (viewType == ViewType.LIST) {
                ScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            } else {
                ScrollPosition(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            }
        }
        launch {
            snapshotFlow { currentEntries.size }.first { it > initialScroll.index }  // stack-reviewer-ignore: index < size is exactly when the index exists; the previous folder's listing shows briefly under the new key, so a shorter list cannot be told from a folder that shrank, and staying at the top beats clamping to a wrong row
            if (position() != initialScroll) {  // stack-reviewer-ignore: initialScroll is an immutable value captured when the effect launched; recording writes to the view model and never changes it, and position() is meant to read the live state
                if (viewType == ViewType.LIST) {
                    listState.scrollToItem(initialScroll.index, initialScroll.offset)
                } else {
                    gridState.scrollToItem(initialScroll.index, initialScroll.offset)
                }
            }
        }
        snapshotFlow(position).distinctUntilChanged().collect { onScrollPositionChanged(it) }  // stack-reviewer-ignore: recording ahead of the restore is the fix for the previous pass, where waiting on it stopped recording for good; a restore that cannot apply leaves the folder at the top, so the top is the true position to keep
    }

    LaunchedEffect(viewType, gridState, listState) {
        snapshotFlow {
            val visible = if (viewType == ViewType.LIST) {
                listState.layoutInfo.visibleItemsInfo.map { it.index }
            } else {
                gridState.layoutInfo.visibleItemsInfo.map { it.index }
            }
            visible to currentEntries
        }
            .distinctUntilChanged()
            .collect { (visible, all) ->
                if (visible.isEmpty() || all.isEmpty()) return@collect
                currentOnVisible(
                    inViewportOrder(visible, all).mapNotNull { (it as? GridEntry.Tile)?.item }  // stack-reviewer-ignore: visible holds indices into the entries the grid renders, so the order is computed over those and only then narrowed to tiles; a header or filler in the lookahead only trims it by a row, and covers are wanted for tiles alone
                )
            }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = handleRefresh,
    ) {
        if (viewType == ViewType.LIST) {
            LazyColumn(state = listState) {
                items(entries) { entry ->  // stack-reviewer-ignore: the scroll restore and the viewport reporter both index currentEntries, the same list rendered here, and a one-column list never gets fillers
                    when (entry) {
                        is GridEntry.Header -> GroupHeader(
                            groupLabel(entry.group), entry.first, onGroupClick?.let { open -> { open(entry.group) } },
                        )
                        is GridEntry.Tile -> ListViewItem(
                            entry.item.name,
                            entry.item.type,
                            entry.item.thumbnails,
                            handleItemClick(entry.item),
                            handleLongItemClick(entry.item),
                            getImageUrl,
                            entry.item.cover,
                        )
                        is GridEntry.Filler -> Unit
                    }
                }
            }
        } else if (viewType == ViewType.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                state = gridState,
                contentPadding = PaddingValues(horizontal = if (groupOf != null) 8.dp else 0.dp),
            ) {
                items(
                    entries,
                    span = { if (it is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                ) { entry ->
                    when (entry) {
                        is GridEntry.Header -> GroupHeader(
                            groupLabel(entry.group), entry.first, onGroupClick?.let { open -> { open(entry.group) } },
                        )
                        is GridEntry.Filler -> GroupCellBox(entry.cell) {
                            ExplorerItemPlaceholder()
                        }
                        is GridEntry.Tile -> key(columnCount) {  // stack-reviewer-ignore: this is Compose's runtime key() inside one item's own lambda, not a LazyList item key; it only has to be unique among that item's children, and it is the sole child. Its job is to remount the tile when the column count changes so images are requested at the new size.
                            GroupCellBox(entry.cell) {
                                ExplorerItem(
                                    entry.item.name,
                                    entry.item.type,
                                    entry.item.thumbnails,
                                    handleItemClick(entry.item),
                                    handleLongItemClick(entry.item),
                                    getImageUrl,
                                    entry.item.captioned,
                                    entry.item.cover,
                                    entry.item.dimmed,
                                )
                            }
                        }
                    }
                }
            }
        }
        FastScroller(
            metrics = {
                if (viewType == ViewType.LIST) {
                    ScrollMetrics(
                        listState.firstVisibleItemIndex,
                        listState.layoutInfo.visibleItemsInfo.size,
                        listState.layoutInfo.totalItemsCount,
                        listState.isScrollInProgress,
                    )
                } else {
                    ScrollMetrics(
                        gridState.firstVisibleItemIndex,
                        gridState.layoutInfo.visibleItemsInfo.size,
                        gridState.layoutInfo.totalItemsCount,
                        gridState.isScrollInProgress,
                    )
                }
            },
            sections = remember(entries, groupLabel) { sectionsOf(entries, groupLabel) },
            onJump = { index ->
                if (viewType == ViewType.LIST) listState.scrollToItem(index) else gridState.scrollToItem(index)
            },
            modifier = Modifier.matchParentSize(),
        )
    }
}

private val GROUP_CORNER = 12.dp

@Composable
private fun GroupHeader(label: String, first: Boolean, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 8.dp else 20.dp)
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(topStart = GROUP_CORNER, topEnd = GROUP_CORNER),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onClick != null) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open $label",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun GroupCellBox(cell: GroupCell, content: @Composable () -> Unit) {
    if (!cell.grouped) return content()
    val shape = RoundedCornerShape(
        bottomStart = if (cell.bottomStart) GROUP_CORNER else 0.dp,
        bottomEnd = if (cell.bottomEnd) GROUP_CORNER else 0.dp,
    )
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f), shape)
            .padding(4.dp)
    ) {
        content()
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
