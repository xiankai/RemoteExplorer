package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction

/**
 * What has been looked at, one row per file actually viewed (#76).
 *
 * Its own table, never columns on [LocalFile]. That row is cache: the reconcile deletes it
 * when a directory is transiently unlisted and resets it when a file changes on the share,
 * which a Syncthing re-write does for free. This is user data that nothing can re-derive, so
 * nothing outside this DAO writes to it, and no reconcile reaches it.
 *
 * Keyed by the file's **canonical** identity ([com.bespectacledcyborg.remoteexplorer.data.Pairings.canonicalize]),
 * split the way [LocalFile] is, so a photo seen through the share and through its Syncthing
 * copy is one row. The split is what lets a folder be a sum over `parentPath` equality, the
 * same scope [DirTagRelation] uses for a gallery.
 *
 * Sparse on purpose: a file never viewed has no row, and reads as zero. Every grouping
 * (folder, tag, anything later) is a query over this table and never a stored rollup.
 *
 * Cascades with its connection: an id SQLite later hands to a new connection must not
 * inherit what was viewed on the old one.
 */
@Entity(
    primaryKeys = ["connectionId", "parentPath", "filename"],
    foreignKeys = [
        ForeignKey(
            entity = Connection::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("openCount"), Index("lastOpenedAt")],
)
data class FileView(
    val connectionId: Int,
    val parentPath: String,
    val filename: String,
    /**
     * Times opened. For an image, a page in the viewer held past the dwell threshold. For
     * anything handed to another app, a launch. That is "launched", kept apart from
     * [watchedSeconds], which is "watched", so the two are never conflated.
     */
    val openCount: Int = 0,
    /**
     * Time spent playing, from bytes served through the loopback server. Per playback
     * session, overlapping ranges are merged before converting, and that session's total is
     * added here. No range is ever stored.
     */
    val watchedSeconds: Double = 0.0,
    /** Distinct viewing days this was opened on, each counted once. */
    val daysViewed: Int = 0,
    /**
     * The viewing day (epoch day number) [daysViewed] last counted. It is decided once, at
     * write time, so a later change of zone or boundary cannot reinterpret history.
     */
    val lastCountedDay: Long? = null,
    /** Epoch millis of the first open, or null for a row only ever watched. */
    val firstOpenedAt: Long? = null,
    /** Epoch millis of the last open, or null for a row only ever watched. */
    val lastOpenedAt: Long? = null,
)

/**
 * The measures summed over some group of [FileView] rows: a file's own row, a folder's
 * direct children, or a tag's members. [lastOpenedAt] is the latest of them.
 */
data class ViewTotals(
    val openCount: Int = 0,
    val watchedSeconds: Double = 0.0,
    val daysViewed: Int = 0,
    val lastOpenedAt: Long? = null,
) {
    companion object {
        val NONE = ViewTotals()
    }
}

/** [ViewTotals] for one folder, keyed by the folder's path. */
data class FolderViewTotals(
    val path: String,
    val openCount: Int,
    val watchedSeconds: Double,
    val daysViewed: Int,
    val lastOpenedAt: Long?,
) {
    fun totals() = ViewTotals(openCount, watchedSeconds, daysViewed, lastOpenedAt)
}

/** [ViewTotals] for one entry of the tag list, keyed by its group (canonical, else tag). */
data class TagViewTotals(
    val groupKey: String,
    val openCount: Int,
    val watchedSeconds: Double,
    val daysViewed: Int,
    val lastOpenedAt: Long?,
) {
    fun totals() = ViewTotals(openCount, watchedSeconds, daysViewed, lastOpenedAt)
}

@Dao
interface FileViewDao {
    @Query(
        "INSERT OR IGNORE INTO FileView (connectionId, parentPath, filename, openCount, watchedSeconds, daysViewed) " +
                "VALUES (:connectionId, :parentPath, :filename, 0, 0, 0)"
    )
    suspend fun ensureRow(connectionId: Int, parentPath: String, filename: String)

    /**
     * One open at [at], on viewing day [day]. `IS` rather than `=` so a row that has never
     * counted a day (NULL) counts this one.
     */
    @Query(
        "UPDATE FileView SET " +
                "openCount = openCount + 1, " +
                "daysViewed = daysViewed + CASE WHEN lastCountedDay IS :day THEN 0 ELSE 1 END, " +
                "lastCountedDay = :day, " +
                "firstOpenedAt = COALESCE(firstOpenedAt, :at), " +
                "lastOpenedAt = :at " +
                "WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename"
    )
    suspend fun countOpen(
        connectionId: Int,
        parentPath: String,
        filename: String,
        at: Long,
        day: Long,
    )

    @Query(
        "UPDATE FileView SET watchedSeconds = watchedSeconds + :seconds " +
                "WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename"
    )
    suspend fun countWatched(connectionId: Int, parentPath: String, filename: String, seconds: Double)

    @Transaction
    suspend fun recordOpen(connectionId: Int, parentPath: String, filename: String, at: Long, day: Long) {
        ensureRow(connectionId, parentPath, filename)
        countOpen(connectionId, parentPath, filename, at, day)
    }

    @Transaction
    suspend fun recordWatched(connectionId: Int, parentPath: String, filename: String, seconds: Double) {
        ensureRow(connectionId, parentPath, filename)
        countWatched(connectionId, parentPath, filename, seconds)
    }

    @Query("SELECT * FROM FileView WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    suspend fun get(connectionId: Int, parentPath: String, filename: String): FileView?

    /** Every viewed file directly inside any of [parentPaths]. */
    @Query("SELECT * FROM FileView WHERE connectionId = :connectionId AND parentPath IN (:parentPaths)")
    suspend fun filesIn(connectionId: Int, parentPaths: List<String>): List<FileView>

    /**
     * Each of [folders] as the sum of the files directly inside it (#76, decision 4). Sum
     * rather than mean, so a large folder tops "most viewed" on file count alone, which is
     * the intended ordering. A folder with nothing viewed returns no row.
     */
    @Query(
        "SELECT parentPath AS path, SUM(openCount) AS openCount, SUM(watchedSeconds) AS watchedSeconds, " +
                "SUM(daysViewed) AS daysViewed, MAX(lastOpenedAt) AS lastOpenedAt " +
                "FROM FileView WHERE connectionId = :connectionId AND parentPath IN (:folders) " +
                "GROUP BY parentPath"
    )
    suspend fun folderTotals(connectionId: Int, folders: List<String>): List<FolderViewTotals>

    /**
     * Each tag-list entry as the sum over the files it lists or contains: files carrying a
     * member of its group, plus files directly inside a gallery carrying one. Resolved
     * through the alias layer here, at query time, because tag and alias rows are re-ingested
     * underneath the facts. A rollup stored against them would be wrong between rebuilds.
     *
     * Keyed the way the list groups: by canonical for a tag in a group, else by the tag.
     * Each file counts once per entry, however many of that entry's members it carries
     * (UNION, not UNION ALL). It starts from [FileView], the small side, rather than from
     * the tag relations.
     */
    @Query(
        "SELECT t.groupKey AS groupKey, SUM(v.openCount) AS openCount, " +
                "SUM(v.watchedSeconds) AS watchedSeconds, SUM(v.daysViewed) AS daysViewed, " +
                "MAX(v.lastOpenedAt) AS lastOpenedAt " +
                "FROM ( " +
                "  SELECT COALESCE(a.canonical, r.tag) AS groupKey, " +
                "         fv.connectionId AS connectionId, fv.parentPath AS parentPath, fv.filename AS filename " +
                "  FROM FileView AS fv " +
                "  INNER JOIN FileTagRelation AS r ON r.connectionId = fv.connectionId " +
                "    AND r.parentPath = fv.parentPath AND r.filename = fv.filename " +
                "  LEFT JOIN TagAlias AS a ON a.tag = r.tag " +
                "  UNION " +
                "  SELECT COALESCE(a.canonical, d.tag) AS groupKey, " +
                "         fv.connectionId AS connectionId, fv.parentPath AS parentPath, fv.filename AS filename " +
                "  FROM FileView AS fv " +
                "  INNER JOIN DirTagRelation AS d ON d.connectionId = fv.connectionId " +
                "    AND d.parentPath = fv.parentPath " +
                "  LEFT JOIN TagAlias AS a ON a.tag = d.tag " +
                ") AS t " +
                "INNER JOIN FileView AS v ON v.connectionId = t.connectionId " +
                "  AND v.parentPath = t.parentPath AND v.filename = t.filename " +
                "GROUP BY t.groupKey"
    )
    suspend fun tagTotals(): List<TagViewTotals>
}
