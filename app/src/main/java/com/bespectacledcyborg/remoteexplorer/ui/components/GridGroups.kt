package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.utils.NaturalOrder
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

internal fun clusteredByParent(files: List<LocalFile>): List<LocalFile> =
    files.groupBy { it.parentPath }
        .toSortedMap(NaturalOrder)
        .values
        .flatten()

internal fun parentGroupOf(file: LocalFile): String = "${file.connectionId}:${file.parentPath}"

internal fun clusteredByParentInOrder(files: List<LocalFile>): List<LocalFile> =
    files.groupBy(::parentGroupOf).values.flatten()

internal data class AddedOn(val start: LocalDate, val label: String)

private const val NAMED_DAYS = 7L

internal fun addedOn(added: Date, today: LocalDate, zone: ZoneId, locale: Locale = Locale.getDefault()): AddedOn {
    val day = minOf(added.toInstant().atZone(zone).toLocalDate(), today)
    val daysAgo = ChronoUnit.DAYS.between(day, today)
    return when {
        daysAgo == 0L -> AddedOn(day, "Today")
        daysAgo == 1L -> AddedOn(day, "Yesterday")
        daysAgo < NAMED_DAYS -> AddedOn(day, day.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
        else -> YearMonth.from(day).let { AddedOn(it.atDay(1), it.toString()) }
    }
}

internal fun clusteredByAddedOn(files: List<LocalFile>, today: LocalDate, zone: ZoneId): List<LocalFile> =
    files.sortedByDescending { addedOn(it.lastModified, today, zone).start }

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
                bottomStart = slot == slots - width,  // stack-reviewer-ignore: slots is always a whole number of rows, so slots - width is the first cell of the last row whether or not the group fills it; GridGroupsTest covers a group that is an exact multiple
                bottomEnd = slot == slots - 1,
            )
            members.getOrNull(slot)?.let { GridEntry.Tile(it, cell) }
                ?: GridEntry.Filler(group, slot, cell)
        }
        listOf(GridEntry.Header(group, first = index == 0)) + cells
    }
}
