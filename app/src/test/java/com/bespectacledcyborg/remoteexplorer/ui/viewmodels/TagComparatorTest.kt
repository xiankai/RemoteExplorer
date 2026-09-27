package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class TagComparatorTest {

    private val tags = listOf(
        LocalTagWithMetadata("Smile", 1, canonical = "female/smile"),
        LocalTagWithMetadata("Original", 1),
        LocalTagWithMetadata("glasses", 1, canonical = "female/glasses"),
        LocalTagWithMetadata("artist:someone", 1),
    )

    /** By the label the list shows, not the member behind it, and not by case. */
    @Test
    fun `ascending sorts by label, ignoring case`() {
        assertEquals(
            listOf("artist:someone", "female/glasses", "female/smile", "Original"),
            tags.sortedWith(tagComparator(SortType.NAME_ASC)).map { it.label },
        )
    }

    @Test
    fun `descending is the reverse`() {
        assertEquals(
            listOf("Original", "female/smile", "female/glasses", "artist:someone"),
            tags.sortedWith(tagComparator(SortType.NAME_DESC)).map { it.label },
        )
    }
}
