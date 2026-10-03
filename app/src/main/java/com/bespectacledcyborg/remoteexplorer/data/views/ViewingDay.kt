package com.bespectacledcyborg.remoteexplorer.data.views

import com.bespectacledcyborg.remoteexplorer.data.db.DEFAULT_VIEWING_DAY_ZONE
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * The viewing day [epochMillis] falls on, as an epoch day number (#76, decision 8).
 *
 * The order matters. Convert to the zone **first**, then subtract the boundary, then take the
 * date. With a noon boundary in JST, 2026-09-30 23:00 and 2026-10-01 02:00 both land on
 * 2026-09-30, and the next day starts at 2026-10-01 12:00. Subtracting in UTC and converting
 * afterwards would put the boundary in the wrong place for any zone with an offset.
 *
 * In a zone with DST, a boundary inside the transition can bucket a view either side of it.
 * `Asia/Tokyo` has no DST, so the default is unaffected.
 */
fun viewingDay(epochMillis: Long, zone: ZoneId, startMinutes: Int): Long =
    Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .toLocalDateTime()
        .minusMinutes(startMinutes.toLong())
        .toLocalDate()
        .toEpochDay()

/** The stored zone, or the default when what is stored does not name one. */
fun viewingZone(id: String?): ZoneId =
    try {
        ZoneId.of(id ?: DEFAULT_VIEWING_DAY_ZONE)
    } catch (e: DateTimeException) {
        ZoneId.of(DEFAULT_VIEWING_DAY_ZONE)
    }
