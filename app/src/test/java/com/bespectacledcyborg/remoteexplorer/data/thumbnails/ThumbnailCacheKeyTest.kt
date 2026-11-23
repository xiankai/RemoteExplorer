package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The cache key decides which picture a grid cell shows, so getting it wrong is silent: the
 * wrong photo appears and nothing errors.
 */
class ThumbnailCacheKeyTest {

    @Test
    fun `a moved file's cached thumbnail is renamed to its new key, replacing a stale one`() {
        val cache = createTempDirectory().toFile()
        val from = FileIdentity(1, "Photos/beach.jpg")
        val to = FileIdentity(1, "Archive/beach.jpg")
        File(cache, thumbnailCacheKey(from.connectionId, from.path)).writeText("beach")
        File(cache, thumbnailCacheKey(to.connectionId, to.path)).writeText("stale")

        moveCachedThumbnails(cache, listOf(from to to, FileIdentity(1, "never-cached.jpg") to FileIdentity(1, "x.jpg")))

        assertFalse(File(cache, thumbnailCacheKey(from.connectionId, from.path)).exists())
        assertEquals("beach", File(cache, thumbnailCacheKey(to.connectionId, to.path)).readText())
        assertEquals(1, cache.list()!!.size)
    }

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
        val suffix = "-$THUMBNAIL_SIZE.jpg"
        assertTrue("hex of 16 bytes plus the size and extension", key.length == 32 + suffix.length)
    }

    @Test
    fun `a thumbnail rendered at another size is a different entry`() {
        // Presence is what marks a thumbnail cached, so a key without the size would go on
        // serving the old 128 px files verbatim after THUMBNAIL_SIZE went up (#73).
        assertNotEquals(
            thumbnailCacheKey(1, "Photos/beach.jpg", size = 128),
            thumbnailCacheKey(1, "Photos/beach.jpg"),
        )
    }

    @Test
    fun `only files at the current size count as current`() {
        assertTrue(isCurrentThumbnailFile(thumbnailCacheKey(1, "Photos/beach.jpg")))
        assertFalse(isCurrentThumbnailFile(thumbnailCacheKey(1, "Photos/beach.jpg", size = 128)))
        // What the cache held before the size was in the name at all.
        assertFalse(isCurrentThumbnailFile("0123456789abcdef0123456789abcdef.jpg"))
    }

    @Test
    fun `the trim deletes files at other sizes and keeps the current ones`() {
        val dir = createTempDirectory("thumbnails").toFile()
        try {
            val current = File(dir, thumbnailCacheKey(1, "a.jpg")).apply { writeText("new") }
            val old = File(dir, thumbnailCacheKey(1, "b.jpg", size = 128)).apply { writeText("old") }
            val unsized = File(dir, "0123456789abcdef0123456789abcdef.jpg").apply { writeText("old") }

            val kept = deleteOtherSizes(arrayOf(current, old, unsized))

            assertEquals(listOf(current), kept)
            assertTrue(current.exists())
            assertFalse(old.exists())
            assertFalse(unsized.exists())
        } finally {
            dir.deleteRecursively()
        }
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
