package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile

private val byParentPath: Comparator<String> =
    compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it }

internal fun clusteredByParent(files: List<LocalFile>): List<LocalFile> =
    files.groupBy { it.parentPath }
        .toSortedMap(byParentPath)
        .values
        .flatten()

internal data class GroupCell(
    val grouped: Boolean,
    val bottomStart: Boolean = false,
    val bottomEnd: Boolean = false,
) {
    companion object {
        val PLAIN = GroupCell(grouped = false)
    }
}

internal sealed interface GridEntry<out T> {
    data class Header(val group: String, val first: Boolean) : GridEntry<Nothing>
    data class Tile<T>(val item: T, val cell: GroupCell) : GridEntry<T>
    data class Filler(val group: String, val slot: Int, val cell: GroupCell) : GridEntry<Nothing>
}

internal fun <T> gridEntries(
    items: List<T>,
    columns: Int,
    groupOf: ((T) -> String)?,
): List<GridEntry<T>> {
    if (groupOf == null) return items.map { GridEntry.Tile(it, GroupCell.PLAIN) }
    val width = columns.coerceAtLeast(1)
    val runs = mutableListOf<Pair<String, MutableList<T>>>()
    items.forEach { item ->
        val group = groupOf(item)
        if (runs.lastOrNull()?.first == group) runs.last().second.add(item)
        else runs.add(group to mutableListOf(item))
    }
    return runs.flatMapIndexed { index, (group, members) ->
        val slots = (members.size + width - 1) / width * width
        val cells = (0 until slots).map { slot ->
            val cell = GroupCell(
                grouped = true,
                bottomStart = slot == slots - width,
                bottomEnd = slot == slots - 1,
            )
            members.getOrNull(slot)?.let { GridEntry.Tile(it, cell) }
                ?: GridEntry.Filler(group, slot, cell)
        }
        listOf(GridEntry.Header(group, first = index == 0)) + cells
    }
}
