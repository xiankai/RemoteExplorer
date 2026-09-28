package com.bespectacledcyborg.remoteexplorer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportOrderTest {
    private val all = (0 until 40).toList()

    @Test
    fun `what is on screen comes first, then a screen below, then half a screen above`() {
        val order = inViewportOrder((10..13).toList(), all)

        assertEquals(listOf(10, 11, 12, 13, 14, 15, 16, 17, 9, 8), order)
    }

    @Test
    fun `the look-ahead stops at the ends of the listing`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), inViewportOrder(listOf(0, 1, 2), all.take(6)))
        assertEquals(listOf(38, 39, 37), inViewportOrder(listOf(38, 39), all))
    }
}
