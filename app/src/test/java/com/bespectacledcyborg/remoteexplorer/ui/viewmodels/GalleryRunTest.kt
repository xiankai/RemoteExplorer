package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryRunTest {
    private val run = GalleryRun("pixiv", listOf("pixiv/a", "pixiv/b", "pixiv/c"))

    @Test
    fun `next and previous step through the listing order`() {
        assertEquals("pixiv/c", run.neighbour("pixiv/b", SwipeStep.NEXT))
        assertEquals("pixiv/a", run.neighbour("pixiv/b", SwipeStep.PREVIOUS))
    }

    @Test
    fun `it stops at both ends instead of wrapping`() {
        assertNull(run.neighbour("pixiv/c", SwipeStep.NEXT))
        assertNull(run.neighbour("pixiv/a", SwipeStep.PREVIOUS))
    }

    @Test
    fun `a folder outside the run has no neighbour`() {
        assertNull(run.neighbour("elsewhere/x", SwipeStep.NEXT))
    }

    @Test
    fun `the run applies only to a gallery opened from its origin`() {
        assertTrue(run.isOpenFrom(listOf("", "pixiv", "pixiv/b")))
        assertFalse(run.isOpenFrom(listOf("", "pixiv")))
        assertFalse(run.isOpenFrom(listOf("", "other", "pixiv/b")))
        assertFalse(run.isOpenFrom(listOf("", "pixiv", "pixiv/b", "pixiv/b/deeper")))
        assertFalse(run.isOpenFrom(listOf("pixiv/b")))
    }

    @Test
    fun `a leftward drag past the threshold is next, a rightward one previous`() {
        assertEquals(SwipeStep.NEXT, swipeStepFor(-120f, 100f))
        assertEquals(SwipeStep.PREVIOUS, swipeStepFor(120f, 100f))
    }

    @Test
    fun `a short drag does nothing`() {
        assertNull(swipeStepFor(-99f, 100f))
        assertNull(swipeStepFor(99f, 100f))
        assertNull(swipeStepFor(0f, 100f))
    }
}
