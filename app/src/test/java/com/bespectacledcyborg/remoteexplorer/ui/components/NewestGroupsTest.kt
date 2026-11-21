package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class NewestGroupsTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val tuesday = LocalDate.of(2026, 11, 17)

    private fun at(text: String): Date = Date.from(LocalDateTime.parse(text).atZone(zone).toInstant())

    private fun labelOf(text: String, today: LocalDate = tuesday) =
        addedOn(at(text), today, zone, Locale.ENGLISH).label

    private fun gallery(connectionId: Int, parent: String, name: String, added: String = "2026-11-17T09:00") =
        LocalFile(
            connectionId = connectionId, parentPath = parent, filename = name, type = FileType.FOLDER,
            canHaveThumbnail = false, lastModified = at(added),
        )

    @Test
    fun `the last two days are Today and Yesterday`() {
        assertEquals("Today", labelOf("2026-11-17T00:01"))
        assertEquals("Yesterday", labelOf("2026-11-16T23:59"))
    }

    @Test
    fun `yesterday on a Monday is the Sunday before, not a weekday name`() {
        assertEquals("Yesterday", labelOf("2026-11-15T12:00", today = LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun `the rest of the last week is named by weekday`() {
        assertEquals("Sunday", labelOf("2026-11-15T12:00"))
        assertEquals("Wednesday", labelOf("2026-11-11T12:00"))
    }

    @Test
    fun `a week ago or older falls into its month, even within this month`() {
        assertEquals("2026-11", labelOf("2026-11-10T12:00"))
        assertEquals("2026-09", labelOf("2026-09-30T12:00"))
    }

    @Test
    fun `a date in the future counts as today`() {
        assertEquals("Today", labelOf("2026-11-19T12:00"))
    }

    @Test
    fun `the day is read in the given zone`() {
        val lateUtcMonday = Date.from(LocalDateTime.parse("2026-11-16T16:00").atZone(ZoneId.of("UTC")).toInstant())

        assertEquals("Today", addedOn(lateUtcMonday, tuesday, zone, Locale.ENGLISH).label)
    }

    @Test
    fun `date buckets run newest first whatever the sort, keeping the sort inside each`() {
        val byName = listOf(
            gallery(1, "p", "a", "2026-09-01T12:00"),
            gallery(1, "p", "b", "2026-11-17T08:00"),
            gallery(1, "p", "c", "2026-11-16T08:00"),
            gallery(1, "p", "d", "2026-11-17T07:00"),
        )

        val clustered = clusteredByAddedOn(byName, emptyMap(), tuesday, zone).map { it.filename }

        assertEquals(listOf("b", "d", "c", "a"), clustered)
    }

    @Test
    fun `date buckets read the created date over the modified one`() {
        val galleries = listOf(
            gallery(1, "p", "a", "2026-11-17T08:00"),
            gallery(1, "p", "b", "2026-09-01T12:00"),
        )
        val created = mapOf("p/b" to at("2026-11-17T09:00"), "p/a" to at("2026-09-01T12:00"))

        val clustered = clusteredByAddedOn(galleries, created, tuesday, zone).map { it.filename }

        assertEquals(listOf("b", "a"), clustered)
    }

    @Test
    fun `parent clusters keep the order their first gallery arrived in`() {
        val newestFirst = listOf(
            gallery(1, "z", "1"),
            gallery(1, "a", "2"),
            gallery(1, "z", "3"),
            gallery(2, "a", "4"),
            gallery(1, "a", "5"),
        )

        val clustered = clusteredByParentInOrder(newestFirst).map { it.filename }

        assertEquals(listOf("1", "3", "2", "5", "4"), clustered)
    }

    @Test
    fun `the same parent path on two connections is two groups`() {
        assertNotEquals(parentGroupOf(gallery(1, "a", "x")), parentGroupOf(gallery(2, "a", "y")))
    }
}
