package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_SIZE
import java.io.File
import java.security.MessageDigest

/**
 * Names the cache file holding one remote file's thumbnail.
 *
 * Two things were wrong with naming it after `path.hashCode()`:
 *
 *  - **It is 32 bits.** By the birthday bound a library needs only tens of thousands of
 *    files before a collision is likelier than not, and a collision here shows the wrong
 *    photo rather than failing — a media NAS reaches that size easily.
 *  - **It ignored the connection.** Two shares that both have `Photos/IMG_0001.jpg` shared
 *    one cache entry, so browsing the second showed the first one's picture.
 *
 * A digest over both fixes both. It is truncated because the full 256 bits buys nothing: at
 * 128 bits a collision is not a thing that happens to a photo library.
 */
/*
 * [connectionId] and [path] are a file's CANONICAL name (#51): a file under a paired local
 * folder is keyed as its remote twin, so the two share one entry. That mapping is
 * `Pairings.canonicalize`, applied once, by the server, before anything reaches the cache —
 * this function only digests what it is given.
 */
/*
 * The name also carries the size the thumbnail was rendered at, so raising [THUMBNAIL_SIZE]
 * misses every file rendered before it rather than serving them on verbatim (#73). Presence
 * is what marks a thumbnail cached, so a key without the size would keep handing out the old
 * 128 px files forever. [isCurrentThumbnailFile] is how the trim recognises the leftovers.
 */
internal fun thumbnailCacheKey(
    connectionId: Int,
    path: String,
    size: Int = THUMBNAIL_SIZE,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$connectionId\u0000$path".toByteArray(Charsets.UTF_8))

    return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) } + suffixFor(size)
}

/** Whether a cache file was rendered at the current [THUMBNAIL_SIZE]. */
internal fun isCurrentThumbnailFile(name: String): Boolean = name.endsWith(suffixFor(THUMBNAIL_SIZE))

/**
 * Deletes the files among [files] rendered at another [THUMBNAIL_SIZE] and returns the rest.
 *
 * Nothing will look those up again (#73), so they go whatever the cache's total, rather than
 * squatting on the ceiling until age pushes them out. One that will not delete is kept in
 * the result, so the trim still counts the space it takes.
 */
internal fun deleteOtherSizes(files: Array<File>): List<File> {
    val (current, stale) = files.partition { isCurrentThumbnailFile(it.name) }
    return current + stale.filterNot { it.delete() }
}

private fun suffixFor(size: Int) = "-$size.jpg"

private const val KEY_BYTES = 16
