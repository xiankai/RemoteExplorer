package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_SIZE
import com.bespectacledcyborg.remoteexplorer.data.tasks.thumbnailScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What size a thumbnail comes out at, and which sources are allowed to produce it (#73). */
class ThumbnailScaleTest {

    @Test
    fun `a large image is scaled so its long side is the thumbnail size`() {
        assertEquals(THUMBNAIL_SIZE.toFloat(), 6000 * thumbnailScale(6000, 4000), 0.5f)
        assertEquals(THUMBNAIL_SIZE.toFloat(), 4000 * thumbnailScale(3000, 4000), 0.5f)
    }

    @Test
    fun `a small image is never scaled up`() {
        assertEquals(1f, thumbnailScale(160, 120), 0f)
        assertEquals(1f, thumbnailScale(THUMBNAIL_SIZE, THUMBNAIL_SIZE), 0f)
    }

    @Test
    fun `a camera's 160 px embedded thumbnail is too small to stand in`() {
        assertFalse(isEmbeddedLargeEnough(160, 120))
    }

    @Test
    fun `an embedded preview at least the thumbnail size is used`() {
        assertTrue(isEmbeddedLargeEnough(THUMBNAIL_SIZE, THUMBNAIL_SIZE / 2))
        assertTrue(isEmbeddedLargeEnough(1920, 1080))
    }
}
