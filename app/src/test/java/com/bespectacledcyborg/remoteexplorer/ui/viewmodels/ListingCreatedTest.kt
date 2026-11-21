package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.newestAdditionsPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ListingCreatedTest {

    private fun folder(name: String) = LocalFile(
        connectionId = 1, parentPath = "p", filename = name, type = FileType.FOLDER,
        canHaveThumbnail = false, lastModified = Date(0),
    )

    private val listing = listOf(folder("a"), folder("b"))

    @Test
    fun `a sort that never reads created dates never waits for them`() {
        assertTrue(ListingCreated.NONE.readyFor(listing, SortType.NAME_ASC, "p"))
    }

    @Test
    fun `recently created waits until the dates are for this listing`() {
        assertFalse(ListingCreated.NONE.readyFor(listing, SortType.ADDED_DESC, "p"))
        assertFalse(ListingCreated(listOf(folder("a")), emptyMap()).readyFor(listing, SortType.ADDED_DESC, "p"))
        assertTrue(ListingCreated(listing.toList(), emptyMap()).readyFor(listing, SortType.ADDED_DESC, "p"))
    }

    @Test
    fun `newest additions waits for its dates whatever the sort`() {
        assertFalse(ListingCreated.NONE.readyFor(listing, SortType.NAME_ASC, newestAdditionsPath))
        assertTrue(ListingCreated(listing, emptyMap()).readyFor(listing, SortType.NAME_ASC, newestAdditionsPath))
    }
}
