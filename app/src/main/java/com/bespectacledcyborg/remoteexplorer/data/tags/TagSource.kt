package com.bespectacledcyborg.remoteexplorer.data.tags

/**
 * Where one connection's tags come from.
 *
 * Tags are read back out of Room in every case — a source's job is to keep that index up to
 * date, not to answer queries. That keeps browsing by tag instant and available offline
 * whichever source filled it, and means a remote backend is a faster way to populate the
 * same tables rather than a second read path to maintain.
 *
 * A share with no tags at all is a first-class case, not an error: a source with nothing to
 * index reports zero and says nothing.
 */
interface TagSource {
    /**
     * Advances the tag index for [connectionId], doing at most [limit] files' worth of work
     * where the work divides into files at all.
     *
     * [reindexAll] re-reads what has already been indexed, for a user-requested refresh;
     * otherwise only what has not been processed is considered.
     */
    suspend fun index(connectionId: Int, reindexAll: Boolean, limit: Int): TagIndexProgress
}

/**
 * What one pass achieved.
 *
 * [mayHaveMore] is separate from [indexed] because the two are not the same question, and
 * conflating them was a bug waiting to happen: the caller used to infer "there is more" from
 * the count equalling the batch size, which works only for a source that processes files one
 * at a time and can stop between any two. A source that fetches a whole index in one go
 * returns a count that has nothing to do with the limit, and would have silently stopped a
 * sidecar walk sharing the same pass.
 */
data class TagIndexProgress(
    /** How many files or records this pass dealt with. Zero means it found nothing to do. */
    val indexed: Int,
    /** Whether another pass would find more. */
    val mayHaveMore: Boolean,
) {
    companion object {
        val NOTHING = TagIndexProgress(indexed = 0, mayHaveMore = false)
    }
}

/**
 * Chooses the source for a connection.
 *
 * Every connection resolves to the same [CombinedTagSource] today — index, aliases and
 * annotations, each finding nothing on a share that lacks it. The seam exists because the
 * choice becomes per-connection once a remote tag database can be configured, and that
 * wants somewhere to live that is not a call site.
 */
fun interface TagSources {
    fun forConnection(connectionId: Int): TagSource
}
