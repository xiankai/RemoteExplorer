package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus

/**
 * Remembers which files have already been found to have no thumbnail.
 *
 * `ThumbnailStatus` has carried `ERROR` and `UNSUPPORTED` since long before anything wrote
 * them from the serving path, so a file nothing could decode was re-attempted every single
 * time its cell scrolled back into view — the full read and decode, for a guaranteed
 * failure. The fallback chain made that worse rather than better, because a hopeless file
 * now costs two decoders instead of one.
 *
 * Deliberately not part of [ThumbnailSource]: a source's job is to render, and whether an
 * answer is worth remembering is the caller's concern.
 */
interface ThumbnailStatusStore {
    /** null when the file is not in the listing cache at all. */
    suspend fun statusOf(connectionId: Int, path: String): ThumbnailStatus?

    suspend fun record(connectionId: Int, path: String, status: ThumbnailStatus)
}
