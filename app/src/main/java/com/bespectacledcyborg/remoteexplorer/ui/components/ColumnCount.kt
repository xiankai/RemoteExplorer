package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.bespectacledcyborg.remoteexplorer.data.db.AUTO_COLUMN_COUNT

private const val AUTO_TILE_WIDTH_DP = 180

val columnCountOptions: List<Int> = listOf(AUTO_COLUMN_COUNT) + (1..12)

fun columnCountLabel(columnCount: Int): String =
    if (columnCount == AUTO_COLUMN_COUNT) "Auto" else columnCount.toString()

@Composable
fun resolveColumnCount(columnCount: Int): Int {
    if (columnCount != AUTO_COLUMN_COUNT) return columnCount
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return (screenWidthDp / AUTO_TILE_WIDTH_DP).coerceIn(2, 12)
}
