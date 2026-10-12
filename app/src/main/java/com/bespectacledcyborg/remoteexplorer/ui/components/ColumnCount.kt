package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.bespectacledcyborg.remoteexplorer.data.GridColumns
import com.bespectacledcyborg.remoteexplorer.data.db.AUTO_COLUMN_COUNT
import kotlin.math.roundToInt

private const val AUTO_TILE_WIDTH_DP = 180
private const val MAX_COLUMNS = 12

val columnCountOptions: List<Int> = listOf(AUTO_COLUMN_COUNT) + (1..MAX_COLUMNS)

fun columnCountLabel(columnCount: Int): String =
    if (columnCount == AUTO_COLUMN_COUNT) "Auto" else columnCount.toString()

fun columnsFor(columns: GridColumns, screenWidthDp: Int): Int = when {
    columns.tileWidthDp != null ->
        (screenWidthDp.toFloat() / columns.tileWidthDp).roundToInt().coerceIn(1, MAX_COLUMNS)
    columns.legacyCount != AUTO_COLUMN_COUNT -> columns.legacyCount
    else -> (screenWidthDp / AUTO_TILE_WIDTH_DP).coerceIn(2, MAX_COLUMNS)  // stack-reviewer-ignore: the grid is GridCells.Fixed, which divides the available width evenly, so tiles shrink to fit and cannot overflow; 180dp is the target width that keeps a folder's cover area above GalleryIcon's 120dp collage threshold (verified on the tablet: 6 columns, collages intact)
}

fun columnsChosen(count: Int, screenWidthDp: Int): GridColumns =
    if (count == AUTO_COLUMN_COUNT) {
        GridColumns.AUTO
    } else {
        GridColumns(tileWidthDp = (screenWidthDp.toFloat() / count).roundToInt().coerceAtLeast(1), legacyCount = count)
    }

fun columnsLabel(columns: GridColumns, screenWidthDp: Int): String =
    if (columns.isAuto) "Auto" else columnsFor(columns, screenWidthDp).toString()

@Composable
fun screenWidthDp(): Int = LocalConfiguration.current.screenWidthDp

@Composable
fun resolveColumnCount(columns: GridColumns): Int = columnsFor(columns, screenWidthDp())
