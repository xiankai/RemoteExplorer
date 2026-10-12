package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.ViewTotals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class ViewComparatorTest {
    private fun file(name: String) = LocalFile(1, "Photos", name, FileType.FILE, true, lastModified = Date(0))

    private val files = listOf(file("a.jpg"), file("b.jpg"), file("c.jpg"), file("d.jpg"))
    private val views = mapOf(
        "Photos/a.jpg" to ViewTotals(openCount = 3, daysViewed = 1, lastOpenedAt = 10),
        "Photos/b.jpg" to ViewTotals(openCount = 3, daysViewed = 3, lastOpenedAt = 30),
        "Photos/c.jpg" to ViewTotals(openCount = 1, daysViewed = 1, lastOpenedAt = 50, watchedSeconds = 90.0),
        // d.jpg never opened
    )

    private fun order(sort: SortType) = files.sortedWith(fileComparator(sort, views)).map { it.filename }

    @Test
    fun `most viewed breaks a tie by the more recent open`() =
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg", "d.jpg"), order(SortType.MOST_VIEWED))

    @Test
    fun `least viewed puts never-opened first`() =
        assertEquals(listOf("d.jpg", "c.jpg", "a.jpg", "b.jpg"), order(SortType.LEAST_VIEWED))

    @Test
    fun `recently viewed puts never-opened last`() =
        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg", "d.jpg"), order(SortType.RECENTLY_VIEWED))

    @Test
    fun `most days and most watched read their own measure`() {
        assertEquals("b.jpg", order(SortType.MOST_DAYS_VIEWED).first())
        assertEquals("c.jpg", order(SortType.MOST_WATCHED).first())
    }

    @Test
    fun `tags sort by their group's totals, falling back to the name`() {
        val tags = listOf(
            LocalTagWithMetadata("x", 1, canonical = "people/alice"),
            LocalTagWithMetadata("bob", 1),
            LocalTagWithMetadata("carol", 1),
        )
        val totals = mapOf("people/alice" to ViewTotals(openCount = 2), "bob" to ViewTotals(openCount = 5))
        assertEquals(
            listOf("bob", "people/alice", "carol"),
            tags.sortedWith(tagComparator(SortType.MOST_VIEWED, totals)).map { it.label },
        )
    }
}
