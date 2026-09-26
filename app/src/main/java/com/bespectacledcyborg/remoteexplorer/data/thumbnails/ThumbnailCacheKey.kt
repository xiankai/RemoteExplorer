package com.bespectacledcyborg.remoteexplorer.data.thumbnails

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
internal fun thumbnailCacheKey(connectionId: Int, path: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$connectionId\u0000$path".toByteArray(Charsets.UTF_8))

    return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) } + ".jpg"
}

private const val KEY_BYTES = 16
