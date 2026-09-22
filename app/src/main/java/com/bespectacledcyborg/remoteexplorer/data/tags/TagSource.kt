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
    val capabilities: TagSourceCapabilities

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
 * What a source can do. Read by the UI so it can distinguish "this share has no tags" from
 * "this source has only seen part of the share" — a difference the app used to hide.
 */
data class TagSourceCapabilities(
    /**
     * Whether the source can find tags in directories the app has never browsed to.
     *
     * False for sidecars, which are only discovered by listing the directory holding them,
     * so the tag list covers visited folders and nothing else.
     */
    val indexesUnvisitedDirectories: Boolean,

    /** Whether a tag can be written back. Nothing does yet; see #29. */
    val supportsWrites: Boolean,
)

/**
 * Chooses the source for a connection.
 *
 * Only sidecars are implemented, so every connection resolves to the same source today. The
 * seam exists because the choice becomes per-connection once a remote tag database can be
 * configured, and that wants somewhere to live that is not a call site.
 */
fun interface TagSources {
    fun forConnection(connectionId: Int): TagSource
}
