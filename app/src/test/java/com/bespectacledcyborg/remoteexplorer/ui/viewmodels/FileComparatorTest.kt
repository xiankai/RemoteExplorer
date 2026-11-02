package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class FileComparatorTest {

    private fun file(name: String, modified: Long) = LocalFile(
        connectionId = 1, parentPath = "", filename = name, type = FileType.FILE,
        canHaveThumbnail = true, lastModified = Date(modified),
    )

    private val files = listOf(file("b", 2), file("a", 3), file("d", 1), file("c", 2))

    private fun sorted(sortType: SortType) = files.sortedWith(fileComparator(sortType)).map { it.filename }

    @Test
    fun `name orders`() {
        assertEquals(listOf("a", "b", "c", "d"), sorted(SortType.NAME_ASC))
        assertEquals(listOf("d", "c", "b", "a"), sorted(SortType.NAME_DESC))
    }

    @Test
    fun `numbers in names order by value`() {
        val numbered = listOf("101.jpg", "2.jpg", "10.jpg", "1.jpg", "100.jpg").map { file(it, 0) }

        val ascending = numbered.sortedWith(fileComparator(SortType.NAME_ASC)).map { it.filename }
        val descending = numbered.sortedWith(fileComparator(SortType.NAME_DESC)).map { it.filename }

        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "100.jpg", "101.jpg"), ascending)
        assertEquals(listOf("101.jpg", "100.jpg", "10.jpg", "2.jpg", "1.jpg"), descending)
    }

    /** b and c share a time, so they fall back to the name either way round. */
    @Test
    fun `time orders, ties by name`() {
        assertEquals(listOf("d", "b", "c", "a"), sorted(SortType.LAST_MODIFIED_ASC))
        assertEquals(listOf("a", "b", "c", "d"), sorted(SortType.LAST_MODIFIED_DESC))
    }
}
