package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.data.GridColumns
import com.bespectacledcyborg.remoteexplorer.data.db.AUTO_COLUMN_COUNT
import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnCountTest {
    private val tabletPortrait = 800
    private val tabletLandscape = 1280
    private val phonePortrait = 411

    @Test
    fun `a count picked in portrait keeps its tile width in landscape`() {
        val picked = columnsChosen(4, tabletPortrait)
        assertEquals(4, columnsFor(picked, tabletPortrait))
        assertEquals(6, columnsFor(picked, tabletLandscape))
    }

    @Test
    fun `a count picked in landscape comes back in portrait`() {
        val picked = columnsChosen(8, tabletLandscape)
        assertEquals(8, columnsFor(picked, tabletLandscape))
        assertEquals(5, columnsFor(picked, tabletPortrait))
        assertEquals(3, columnsFor(picked, phonePortrait))
    }

    @Test
    fun `the count never leaves one to twelve`() {
        assertEquals(12, columnsFor(columnsChosen(12, phonePortrait), tabletLandscape))
        assertEquals(1, columnsFor(columnsChosen(1, tabletLandscape), phonePortrait))
    }

    @Test
    fun `a count stored before tile widths stays fixed`() {
        val legacy = GridColumns(tileWidthDp = null, legacyCount = 6)
        assertEquals(6, columnsFor(legacy, tabletPortrait))
        assertEquals(6, columnsFor(legacy, tabletLandscape))
        assertEquals("6", columnsLabel(legacy, tabletPortrait))
    }

    @Test
    fun `auto follows the width`() {
        assertEquals(GridColumns.AUTO, columnsChosen(AUTO_COLUMN_COUNT, tabletPortrait))
        assertEquals(4, columnsFor(GridColumns.AUTO, tabletPortrait))
        assertEquals(7, columnsFor(GridColumns.AUTO, tabletLandscape))
        assertEquals("Auto", columnsLabel(GridColumns.AUTO, tabletLandscape))
    }
}
