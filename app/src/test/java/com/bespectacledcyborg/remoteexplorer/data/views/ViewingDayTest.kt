package com.bespectacledcyborg.remoteexplorer.data.views

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ViewingDayTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val noon = 12 * 60

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun day(y: Int, mo: Int, d: Int) = LocalDate.of(y, mo, d).toEpochDay()

    @Test
    fun `23-00 and 02-00 JST are the same viewing day, and noon starts the next`() {
        assertEquals(day(2026, 9, 30), viewingDay(at(tokyo, 2026, 9, 30, 23), tokyo, noon))
        assertEquals(day(2026, 9, 30), viewingDay(at(tokyo, 2026, 10, 1, 2), tokyo, noon))
        assertEquals(day(2026, 9, 30), viewingDay(at(tokyo, 2026, 10, 1, 11, 59), tokyo, noon))
        assertEquals(day(2026, 10, 1), viewingDay(at(tokyo, 2026, 10, 1, 12), tokyo, noon))
    }

    /**
     * Subtracting the offset in UTC and converting afterwards would put JST's boundary at
     * 21:00. Converting first keeps it at noon local time.
     */
    @Test
    fun `the boundary is local noon, not UTC noon`() {
        assertEquals(day(2026, 9, 30), viewingDay(at(tokyo, 2026, 10, 1, 11), tokyo, noon))
        assertEquals(day(2026, 10, 1), viewingDay(at(tokyo, 2026, 10, 1, 13), tokyo, noon))
    }

    @Test
    fun `a midnight boundary is the calendar date`() {
        assertEquals(day(2026, 10, 1), viewingDay(at(tokyo, 2026, 10, 1, 0, 1), tokyo, 0))
    }

    @Test
    fun `an unknown zone falls back to Asia-Tokyo`() {
        assertEquals(tokyo, viewingZone("Not/AZone"))
        assertEquals(ZoneId.of("Europe/London"), viewingZone("Europe/London"))
    }
}
