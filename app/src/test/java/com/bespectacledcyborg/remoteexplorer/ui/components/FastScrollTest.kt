package com.bespectacledcyborg.remoteexplorer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScrollTest {

    private val grouped = gridEntries(
        listOf("a1", "a2", "a3", "b1", "c1", "c2"),
        columns = 2,
    ) { it.take(1) }

    private val sections = sectionsOf(grouped) { it.uppercase() }

    @Test
    fun `sections sit at the header indices, counting headers and fillers`() {
        assertEquals(
            listOf(Section(0, "A"), Section(5, "B"), Section(8, "C")),
            sections,
        )
    }

    @Test
    fun `an ungrouped listing has no sections`() {
        assertEquals(emptyList<Section>(), sectionsOf(gridEntries(listOf("x", "y"), 3, null)) { it })
    }

    @Test
    fun `the section at an index is the last header at or before it`() {
        assertEquals("A", sectionAt(sections, 4)?.label)
        assertEquals("B", sectionAt(sections, 5)?.label)
        assertEquals("B", sectionAt(sections, 7)?.label)
        assertEquals("C", sectionAt(sections, 10)?.label)
    }

    @Test
    fun `the ends of the track reach the first and the last section`() {
        val total = grouped.size
        assertEquals(FastScrollTarget(0, "A"), fastScrollTarget(0f, total, sections))
        assertEquals(FastScrollTarget(8, "C"), fastScrollTarget(1f, total, sections))
    }

    @Test
    fun `a grouped drag jumps to the header of the section under it`() {
        assertEquals(FastScrollTarget(5, "B"), fastScrollTarget(0.6f, grouped.size, sections))
    }

    @Test
    fun `an ungrouped drag scrolls in proportion with no label`() {
        assertEquals(FastScrollTarget(50, null), fastScrollTarget(0.5f, 101, emptyList()))
        assertEquals(FastScrollTarget(100, null), fastScrollTarget(2f, 101, emptyList()))
        assertEquals(FastScrollTarget(0, null), fastScrollTarget(-1f, 101, emptyList()))
    }

    @Test
    fun `an empty listing has no target`() {
        assertNull(fastScrollTarget(0.5f, 0, emptyList()))
    }

    @Test
    fun `a listing that fits on screen gets no thumb`() {
        assertFalse(canFastScroll(visible = 0, total = 0))
        assertFalse(canFastScroll(visible = 10, total = 10))
        assertTrue(canFastScroll(visible = 10, total = 11))
        assertEquals(0f, thumbFraction(first = 0, visible = 10, total = 10))
        assertEquals(1f, thumbLengthFraction(visible = 10, total = 10))
    }

    @Test
    fun `the thumb runs from the top to the bottom of the track`() {
        assertEquals(0f, thumbFraction(first = 0, visible = 10, total = 110))
        assertEquals(0.5f, thumbFraction(first = 50, visible = 10, total = 110))
        assertEquals(1f, thumbFraction(first = 100, visible = 10, total = 110))
        assertEquals(1f, thumbFraction(first = 105, visible = 5, total = 110))
    }
}
