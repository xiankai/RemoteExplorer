package com.bespectacledcyborg.remoteexplorer.data.tags

import android.util.Log

/**
 * Runs each source in turn and adds up what they indexed.
 *
 * Not a fallback chain. The sources are not alternatives — each reads its own part of the
 * index, and a share can have any of them or none. The alias layer adds no tags, only which
 * of them mean the same thing.
 */
class CombinedTagSource(
    private val sources: List<TagSource>,
) : TagSource {

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
