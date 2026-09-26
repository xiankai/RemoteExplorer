package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import com.bespectacledcyborg.remoteexplorer.data.Pairings
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus

/**
 * Thumbnail outcomes for a file and every paired copy of it.
 *
 * The server is asked for a file by its canonical name — the remote's — but the listing row
 * that remembers an outcome may be the local copy's: someone browsing only the folder on the
 * device has no rows for the share at all. Recording against the canonical row alone would
 * then record nothing, and a file no decoder can read would be decoded again on every scroll.
 * So an outcome is written to every row the file has, and read from whichever has one.
 *
 * A row that does not exist is simply not written — the store is a cache of the listing, and
 * a file nobody has listed has no status to keep.
 */
class PairedThumbnailStatuses(
    private val rows: ThumbnailStatusStore,
    private val pairings: () -> Pairings,
) : ThumbnailStatusStore {

    /**
     * A failure only when every row that exists says so.
     *
     * Rows go stale independently: Syncthing replacing the local file resets the local row
     * on its next reconcile, and pull-to-refresh clears failures for the one connection it
     * was pulled on. Either is someone saying "try again", and the first row found saying
     * ERROR must not override it — a renderable row wins, and the next [record] brings every
     * row back into step.
     */
    override suspend fun statusOf(connectionId: Int, path: String): ThumbnailStatus? {
        val known = namesOf(connectionId, path).mapNotNull { rows.statusOf(it.connectionId, it.path) }
        return known.firstOrNull { !it.isFailure() } ?: known.firstOrNull()
    }

    override suspend fun record(connectionId: Int, path: String, status: ThumbnailStatus) =
        namesOf(connectionId, path).forEach { rows.record(it.connectionId, it.path, status) }

    private fun ThumbnailStatus.isFailure() =
        this == ThumbnailStatus.ERROR || this == ThumbnailStatus.UNSUPPORTED

    private fun namesOf(connectionId: Int, path: String): List<FileIdentity> {
        val canonical = FileIdentity(connectionId, path)
        return listOf(canonical) + pairings().localCopiesOf(canonical)
    }
}
