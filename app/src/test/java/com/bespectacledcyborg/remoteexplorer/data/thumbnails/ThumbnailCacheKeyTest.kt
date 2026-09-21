package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache key decides which picture a grid cell shows, so getting it wrong is silent: the
 * wrong photo appears and nothing errors.
 */
class ThumbnailCacheKeyTest {

    @Test
    fun `the same file on the same connection keeps the same key`() {
        assertEquals(
            thumbnailCacheKey(1, "Photos/beach.jpg"),
            thumbnailCacheKey(1, "Photos/beach.jpg"),
        )
    }

    @Test
    fun `the same path on two connections does not share a thumbnail`() {
        // Naming the file after the path alone meant two shares that both have
        // Photos/IMG_0001.jpg shared one cache entry, so the second showed the first's photo.
        assertNotEquals(
            thumbnailCacheKey(1, "Photos/IMG_0001.jpg"),
            thumbnailCacheKey(2, "Photos/IMG_0001.jpg"),
        )
    }

    @Test
    fun `different paths get different keys`() {
        assertNotEquals(
            thumbnailCacheKey(1, "Photos/beach.jpg"),
            thumbnailCacheKey(1, "Photos/city.jpg"),
        )
    }

    @Test
    fun `the connection and the path cannot be confused for one another`() {
        // The parts are separated by a byte that cannot occur in either, so no pair of
        // (connection, path) values can produce the same input string as another pair.
        assertNotEquals(
            thumbnailCacheKey(1, "1Photos/beach.jpg"),
            thumbnailCacheKey(11, "Photos/beach.jpg"),
        )
    }

    @Test
    fun `the key is a usable filename`() {
        val key = thumbnailCacheKey(1, "Photos/a b/../weird %20 name.jpg")

        assertTrue("must stay within one path segment", !key.contains('/'))
        assertTrue(key.endsWith(".jpg"))
        assertTrue("hex of 16 bytes plus the extension", key.length == 32 + ".jpg".length)
    }

    @Test
    fun `keys are spread out rather than clustered`() {
        // The point of replacing String.hashCode() was its width: 32 bits collides within a
        // library of tens of thousands of files. This does not prove the absence of
        // collisions, only that a realistically large library has none.
        val keys = (1..20_000).map { thumbnailCacheKey(1, "Photos/IMG_%05d.jpg".format(it)) }

        assertEquals(keys.size, keys.toSet().size)
    }
}
