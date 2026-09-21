package com.bespectacledcyborg.remoteexplorer.data.tags

import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/**
 * Covers which media file a `.xmp` sidecar's tags land on.
 *
 * This used to be a `LIKE '<stem>.%'` query with `LIMIT 1` and no `ORDER BY`, which was wrong
 * in three separate ways — each has a test below. Getting it wrong is quiet: the tags simply
 * attach to the wrong photo, or to nothing, and the Tags screen looks plausible either way.
 */
class SidecarPairingTest {

    private fun file(name: String) = LocalFile(
        connectionId = 1,
        parentPath = "Photos",
        filename = name,
        type = FileType.FILE,
        canHaveThumbnail = true,
        thumbnailStatus = ThumbnailStatus.UNPROCESSED,
        lastModified = Date(0),
    )

    @Test
    fun `pairs a sidecar that replaces the extension`() {
        // Lightroom and Bridge write photo.xmp beside photo.jpg.
        val siblings = listOf(file("beach.jpg"), file("city.jpg"))

        assertEquals("beach.jpg", pairSidecarWithMedia("beach.xmp", siblings)?.filename)
    }

    @Test
    fun `pairs a sidecar that keeps the full filename`() {
        // darktable and digiKam write photo.jpg.xmp. The old pattern reduced this to
        // 'beach.jpg.%', which matches no file at all — so these sidecars were marked REMOVED
        // and their tags silently discarded.
        val siblings = listOf(file("beach.jpg"), file("city.jpg"))

        assertEquals("beach.jpg", pairSidecarWithMedia("beach.jpg.xmp", siblings)?.filename)
    }

    @Test
    fun `does not treat an underscore as a wildcard`() {
        // `_` matches any single character in SQLite's LIKE, so the old query paired
        // IMG_001.xmp with IMGX001.jpg and IMG-001.mp4 just as readily as with the right
        // file — and underscores are in essentially every camera filename.
        val siblings = listOf(file("IMG-001.mp4"), file("IMGX001.jpg"), file("IMG_001.jpg"))

        assertEquals("IMG_001.jpg", pairSidecarWithMedia("IMG_001.xmp", siblings)?.filename)
    }

    @Test
    fun `prefers the exact filename match over a shared stem`() {
        // beach.jpg.xmp describes beach.jpg, even though beach.jpg.bak shares its stem.
        val siblings = listOf(file("beach.jpg"), file("beach.jpg.bak"))

        assertEquals("beach.jpg", pairSidecarWithMedia("beach.jpg.xmp", siblings)?.filename)
    }

    @Test
    fun `resolves a genuine ambiguity the same way every time`() {
        // One sidecar, a photo and a video sharing its stem: nothing can say which was meant,
        // but it must not depend on the order rows come back in. The old query had no
        // ORDER BY, so the tags could move between runs.
        val order = listOf(file("IMG_001.mp4"), file("IMG_001.jpg"))
        val reversed = order.reversed()

        assertEquals("IMG_001.jpg", pairSidecarWithMedia("IMG_001.xmp", order)?.filename)
        assertEquals("IMG_001.jpg", pairSidecarWithMedia("IMG_001.xmp", reversed)?.filename)
    }

    @Test
    fun `finds nothing when the sidecar describes nothing present`() {
        val siblings = listOf(file("city.jpg"))

        assertNull(pairSidecarWithMedia("beach.xmp", siblings))
    }

    @Test
    fun `finds nothing in an empty directory`() {
        assertNull(pairSidecarWithMedia("beach.xmp", emptyList()))
    }
}
