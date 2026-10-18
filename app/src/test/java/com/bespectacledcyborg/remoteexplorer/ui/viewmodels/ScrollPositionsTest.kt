package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollPositionsTest {
    @Test
    fun `going back restores where the parent was left`() {
        val positions = ScrollPositions()
        positions.recordTop(ScrollPosition(42, 17))
        positions.push()
        positions.recordTop(ScrollPosition(3, 0))
        positions.pop()
        assertEquals(ScrollPosition(42, 17), positions.top)
    }

    @Test
    fun `a folder opened afresh starts at the top`() {
        val positions = ScrollPositions()
        positions.recordTop(ScrollPosition(42, 17))
        positions.push()
        assertEquals(ScrollPosition(), positions.top)
    }

    @Test
    fun `the same folder twice on the stack keeps two positions`() {
        val positions = ScrollPositions()
        positions.push()
        positions.recordTop(ScrollPosition(10, 0))
        positions.push()
        positions.push()
        positions.recordTop(ScrollPosition(20, 0))
        positions.push()
        positions.pop()
        assertEquals(ScrollPosition(20, 0), positions.top)
        positions.pop()
        positions.pop()
        assertEquals(ScrollPosition(10, 0), positions.top)
    }

    @Test
    fun `revisiting a folder after leaving it starts at the top`() {
        val positions = ScrollPositions()
        positions.push()
        positions.recordTop(ScrollPosition(30, 5))
        positions.pop()
        positions.push()
        assertEquals(ScrollPosition(), positions.top)
    }

    @Test
    fun `a new connection starts at the top`() {
        val positions = ScrollPositions()
        positions.recordTop(ScrollPosition(30, 5))
        positions.push()
        positions.reset()
        assertEquals(ScrollPosition(), positions.top)
    }
}
