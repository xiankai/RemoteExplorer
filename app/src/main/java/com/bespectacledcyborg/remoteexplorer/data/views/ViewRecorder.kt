package com.bespectacledcyborg.remoteexplorer.data.views

import com.bespectacledcyborg.remoteexplorer.data.ConnectionId
import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import com.bespectacledcyborg.remoteexplorer.data.Pairings
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.FileViewDao
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import com.bespectacledcyborg.remoteexplorer.data.db.ViewTotals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How long a page in the image viewer has to stay settled before it counts as an open
 * (#76, decision 1), so flicking through a folder credits nothing.
 */
const val IMAGE_DWELL_MILLIS = 5_000L

/** SQLite's bind-parameter ceiling is 999. Stay well under it, as the reconcile does. */
private const val IN_CHUNK = 900

/**
 * The one writer of [com.bespectacledcyborg.remoteexplorer.data.db.FileView], and the
 * reads that group it.
 *
 * Every identity handed in is expected to be canonical already. The callers hold the
 * pairing snapshot, and the server canonicalizes on receipt anyway.
 *
 * This is seen data. Keep it out of anything that exports, shares or publishes a listing.
 * The counts are per-device: the share cannot carry them, so they do not follow to another
 * install.
 */
class ViewRecorder(
    private val dao: FileViewDao,
    /** Read on every write rather than once at startup (#71's trap): a changed boundary applies from the next view. */
    private val settings: suspend () -> SettingsEntity?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _changes = MutableStateFlow(0L)

    /** Bumped after every write, so a listing sorted by views can re-read. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** An open: an image held past [IMAGE_DWELL_MILLIS], or the first content served to another app for a file after [OPEN_IDLE_GAP_MILLIS] without any. */
    suspend fun recordOpen(canonical: FileIdentity) {
        val now = clock()
        val current = settings() ?: SettingsEntity()
        val day = viewingDay(now, viewingZone(current.viewingDayZone), current.viewingDayStartMinutes)
        val (parent, name) = split(canonical.path)
        dao.recordOpen(canonical.connectionId, parent, name, now, day)
        _changes.update { it + 1 }
    }

    /** Time spent playing, from one playback session's merged byte ranges. */
    suspend fun recordWatched(canonical: FileIdentity, seconds: Double) {
        if (seconds <= 0.0) return
        val (parent, name) = split(canonical.path)
        dao.recordWatched(canonical.connectionId, parent, name, seconds)
        _changes.update { it + 1 }
    }

    /**
     * What each of [files] adds up to, keyed by [LocalFile.fullPath] as listed. A file is its
     * own row and a folder is the sum of the files directly inside it. Nothing viewed
     * reads as [ViewTotals.NONE], so novelty (fewest opens, with 0 first) needs no special
     * case.
     *
     * [files] are in the frame of [connectionId], the connection being browsed, so each is
     * canonicalized before it is looked up.
     */
    suspend fun totalsFor(
        connectionId: ConnectionId,
        files: List<LocalFile>,
        pairings: Pairings,
    ): Map<String, ViewTotals> {
        val canonical = files.associate { file ->
            file.fullPath() to (file.type to pairings.canonicalize(connectionId, file.fullPath()))
        }
        val result = HashMap<String, ViewTotals>(files.size)

        // Files: one read per directory they sit in, which for a folder listing is one.
        val fileRows = canonical.filterValues { (type, _) -> type != FileType.FOLDER }
        fileRows.values.map { it.second }.groupBy { it.connectionId }.forEach { (conn, ids) ->
            val parents = ids.map { split(it.path).first }.distinct()
            val found = parents.chunked(IN_CHUNK).flatMap { dao.filesIn(conn, it) }
                .associateBy { FileIdentity(it.connectionId, join(it.parentPath, it.filename)) }
            fileRows.forEach { (listed, value) ->
                found[value.second]?.let {
                    result[listed] = ViewTotals(it.openCount, it.watchedSeconds, it.daysViewed, it.lastOpenedAt)
                }
            }
        }

        // Folders: the sum over their direct children, grouped in SQL.
        val folderRows = canonical.filterValues { (type, _) -> type == FileType.FOLDER }
        folderRows.values.map { it.second }.groupBy { it.connectionId }.forEach { (conn, ids) ->
            val found = ids.map { it.path }.distinct().chunked(IN_CHUNK)
                .flatMap { dao.folderTotals(conn, it) }
                .associateBy { FileIdentity(conn, it.path) }
            folderRows.forEach { (listed, value) ->
                found[value.second]?.let { result[listed] = it.totals() }
            }
        }
        return result
    }

    /** Each tag-list entry's totals, keyed by its canonical, else its tag. */
    suspend fun tagTotals(): Map<String, ViewTotals> =
        dao.tagTotals().associate { it.groupKey to it.totals() }

    private companion object {
        /** The inverse of [LocalFile.fullPath]: a file at the root has an empty parentPath. */
        fun split(path: String): Pair<String, String> =
            path.substringBeforeLast('/', "") to path.substringAfterLast('/')

        fun join(parent: String, name: String): String =
            if (parent.isEmpty()) name else "$parent/$name"
    }
}
