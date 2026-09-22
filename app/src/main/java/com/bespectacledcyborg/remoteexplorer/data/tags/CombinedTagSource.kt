package com.bespectacledcyborg.remoteexplorer.data.tags

import android.util.Log

/**
 * Runs each source in turn and adds up what they indexed.
 *
 * Not a fallback chain. The two sources are not alternatives — they describe different parts
 * of a share, and a share can have both: gallery-dl's index covers the galleries it
 * downloaded, and sidecars cover whatever a photo tool wrote next to the files. Both write
 * into the same tables, so running both is the union rather than a conflict.
 *
 * Order is cheapest-first, which also puts the one that can see unbrowsed directories first,
 * so a fresh connection has whole-library tags before the sidecar walk has got anywhere.
 */
class CombinedTagSource(
    private val sources: List<TagSource>,
) : TagSource {

    /**
     * The union of what the sources can do.
     *
     * [TagSourceCapabilities.indexesUnvisitedDirectories] is true if *any* source can,
     * because the question it answers for the UI is whether the tag list might be missing
     * directories nobody has opened — and one source covering them is enough for it not to
     * be. Writes are false until something can write, and then it will be the source's
     * answer rather than this one's.
     */
    override val capabilities = TagSourceCapabilities(
        indexesUnvisitedDirectories = sources.any { it.capabilities.indexesUnvisitedDirectories },
        supportsWrites = sources.any { it.capabilities.supportsWrites },
    )

    /**
     * Indexes with every source, and reports the total.
     *
     * A source that throws stops the pass rather than being skipped: both of these reach the
     * share, so a failure is the share being unreachable, and carrying on to the next source
     * would just fail again more slowly. The worker retries the whole pass.
     */
    override suspend fun index(
        connectionId: Int,
        reindexAll: Boolean,
        limit: Int,
    ): TagIndexProgress {
        var total = 0
        var more = false
        for (source in sources) {
            val progress = source.index(connectionId, reindexAll, limit)
            if (progress.indexed > 0) {
                Log.d(TAG, "${source.javaClass.simpleName} indexed ${progress.indexed}")
            }
            total += progress.indexed
            // One source still having work to do is enough to run the pass again, and the
            // other sources finding nothing on that pass costs a query each.
            more = more || progress.mayHaveMore
        }
        return TagIndexProgress(total, more)
    }

    private companion object {
        const val TAG = "CombinedTagSource"
    }
}
