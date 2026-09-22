package com.bespectacledcyborg.remoteexplorer.data.tags

import LocalDatabaseDataSource
import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.db.DirectoryTagUpdate
import com.bespectacledcyborg.remoteexplorer.data.db.FileTagUpdate
import java.io.File

/**
 * Fills the tag index from the rolled-up log gallery-dl writes to the share.
 *
 * One transfer instead of a sidecar per file, and — the part that matters more — it covers
 * the whole share rather than only directories that have been browsed. The sidecar scanner
 * can only find a sidecar once its directory has been listed, so "browse by tag" has always
 * silently meant "browse by tags in folders I have already opened".
 *
 * A share with no index reports zero and says nothing. This is one library's convention, not
 * something shares are expected to have.
 */
class IndexFileTagSource(
    private val reader: TagIndexReader,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
) : TagSource {

    override val capabilities = TagSourceCapabilities(
        indexesUnvisitedDirectories = true,
        supportsWrites = false,
    )

    /**
     * Pulls whatever the index has that this connection has not seen.
     *
     * [limit] is not honoured, and cannot usefully be: the unit of resumption is a byte
     * offset into the log, so stopping part way would leave nothing meaningful to record and
     * the next pass would start from the same place regardless. There is also little to
     * chop up — the whole log is under a megabyte for a library of well over a thousand
     * galleries, and after the first pull a pass reads only what was appended since.
     *
     * [reindexAll] discards the stored offset and takes everything again.
     *
     * The count it reports can exceed [limit] for the same reason, which is why "is there
     * more" is a separate answer rather than inferred from it.
     */
    override suspend fun index(
        connectionId: Int,
        reindexAll: Boolean,
        limit: Int,
    ): TagIndexProgress {
        val state = if (reindexAll) {
            null
        } else {
            localDatabaseDataSource.getTagIndexIngest(connectionId)?.let {
                TagIngestState(it.generation, it.bytesRead)
            }
        }

        val plan = reader.plan(connectionId, state)
        if (plan is TagIndexPlan.NothingToDo) return TagIndexProgress.NOTHING

        val directories = mutableListOf<DirectoryTagUpdate>()
        val files = mutableListOf<FileTagUpdate>()

        // Collected and then applied in one transaction rather than written as they arrive.
        // The stored offset has to land with the rows it describes: a pass that wrote half
        // its records and then the offset would leave the next one resuming from a point it
        // never actually reached, and the gap would never be noticed. The whole log is small
        // enough that holding a pass in memory costs nothing worth the alternative.
        val carried = reader.read(connectionId, plan) { record ->
            when (record.scope) {
                // The record's `files` count is read and dropped. A tag counts the rows
                // opening it will show, and a gallery is one row however many files it
                // holds, so storing the number would give it no reader.
                TagScope.DIRECTORY -> directories += DirectoryTagUpdate(
                    parentPath = record.path,
                    tags = record.tags,
                    deleted = record.deleted,
                )

                TagScope.FILE -> fileUpdate(record)?.let { files += it }
            }
        }

        val meta = carried.metaOrNull() ?: return TagIndexProgress.NOTHING

        // The plan that was carried out, not the one asked for: a resume the reader found to
        // be misaligned comes back as a full pull, and a full pull replaces rather than adds.
        val replace = carried is TagIndexPlan.FullPull
        if (plan is TagIndexPlan.Resume && replace) {
            Log.w(TAG, "Resume for $connectionId became a full pull; replacing its tags")
        }

        localDatabaseDataSource.applyTagIndex(
            connectionId = connectionId,
            replace = replace,
            directories = directories,
            files = files,
            generation = meta.generation,
            bytesRead = meta.bytes,
        )

        val applied = directories.size + files.size
        Log.i(TAG, "Applied $applied records for connection $connectionId")
        // Never "more": a pass consumes everything the meta described, so another one would
        // resume from the offset just stored and find nothing.
        return TagIndexProgress(applied, mayHaveMore = false)
    }

    private fun fileUpdate(record: TagIndexRecord): FileTagUpdate? {
        val file = File(record.path)
        val parent = file.parent
        if (parent == null) {
            // A file-scoped record at the share root has no directory to key on. Nothing in
            // the real index looks like this, and inventing a parent would attach its tags
            // to a path that does not exist.
            Log.w(TAG, "File record with no directory: ${record.path}")
            return null
        }
        return FileTagUpdate(parent, file.name, record.tags, record.deleted)
    }

    private companion object {
        const val TAG = "IndexFileTagSource"
    }
}

/** The meta a plan carried, if it had one — [TagIndexPlan.NothingToDo] does not. */
private fun TagIndexPlan.metaOrNull(): TagIndexMeta? = when (this) {
    is TagIndexPlan.FullPull -> meta
    is TagIndexPlan.Resume -> meta
    is TagIndexPlan.NothingToDo -> null
}
