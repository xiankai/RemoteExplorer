package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.data.Pairings
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairedThumbnailStatusesTest {

    /** Rows that exist, by (connection, path). Absent keys are files nobody listed. */
    private val rows = mutableMapOf<Pair<Int, String>, ThumbnailStatus>()
    private val store = object : ThumbnailStatusStore {
        override suspend fun statusOf(connectionId: Int, path: String) = rows[connectionId to path]
        override suspend fun record(connectionId: Int, path: String, status: ThumbnailStatus) {
            rows.computeIfPresent(connectionId to path) { _, _ -> status }
        }
    }
    private val statuses = PairedThumbnailStatuses(store) { Pairings(listOf(Pairing(3, "", 1, "Library"))) }

    @Test
    fun `an outcome is found on the local row when only the copy was ever listed`() = runTest {
        rows[3 to "clip.avi"] = ThumbnailStatus.ERROR

        assertEquals(ThumbnailStatus.ERROR, statuses.statusOf(1, "Library/clip.avi"))
    }

    @Test
    fun `an outcome is written to every row the file has`() = runTest {
        rows[1 to "Library/clip.avi"] = ThumbnailStatus.UNPROCESSED
        rows[3 to "clip.avi"] = ThumbnailStatus.UNPROCESSED

        statuses.record(1, "Library/clip.avi", ThumbnailStatus.SUCCESS)

        assertEquals(ThumbnailStatus.SUCCESS, rows[1 to "Library/clip.avi"])
        assertEquals(ThumbnailStatus.SUCCESS, rows[3 to "clip.avi"])
    }

    @Test
    fun `one row asking for another try beats an old failure on the other`() = runTest {
        // The share recorded a failure; since then the local copy was replaced and its
        // reconcile reset the row. That is a request to try again, and must be honoured.
        rows[1 to "Library/clip.avi"] = ThumbnailStatus.ERROR
        rows[3 to "clip.avi"] = ThumbnailStatus.UNPROCESSED

        assertEquals(ThumbnailStatus.UNPROCESSED, statuses.statusOf(1, "Library/clip.avi"))
    }

    @Test
    fun `a file nobody has listed has no status`() = runTest {
        assertNull(statuses.statusOf(1, "Library/clip.avi"))
    }
}
