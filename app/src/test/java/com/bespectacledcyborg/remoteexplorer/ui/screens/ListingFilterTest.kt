package com.bespectacledcyborg.remoteexplorer.ui.screens

import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class ListingFilterTest {
    private fun entry(name: String, type: FileType = FileType.FILE) = LocalFile(
        connectionId = 1,
        parentPath = "/share",
        filename = name,
        type = type,
        canHaveThumbnail = false,
        lastModified = Date(0),
    )

    private fun byExtension(name: String) = when (name.substringAfterLast('.', "")) {
        "mp4" -> IconType.VIDEO
        "jpg" -> IconType.IMAGE
        else -> IconType.FOLDER
    }

    private val gallery = entry("gallery", FileType.FOLDER)
    private val dottedGallery = entry("trip.2024", FileType.FOLDER)
    private val clip = entry("clip.mp4")
    private val picture = entry("picture.jpg")
    private val listing = listOf(gallery, dottedGallery, clip, picture)

    @Test
    fun `a type filter keeps every folder alongside the matching files`() {
        assertEquals(
            listOf(gallery, dottedGallery, clip),
            listing.keepingTypes(listOf(IconType.VIDEO), ::byExtension),
        )
    }

    @Test
    fun `no type filter keeps everything`() {
        assertEquals(listing, listing.keepingTypes(emptyList()))
    }

    @Test
    fun `search matches names case-insensitively`() {
        assertEquals(listOf(clip), listing.matchingSearch("CLIP"))
        assertEquals(listing, listing.matchingSearch(""))
    }

    @Test
    fun `a search that empties a folder says so`() {
        assertEquals(
            "Nothing matches \"absent\"",
            emptyResultMessage("absent", emptyList(), folderIsEmpty = false, resultIsEmpty = true),
        )
    }

    @Test
    fun `a type filter that empties a folder names the types`() {
        assertEquals(
            "No video or audio here",
            emptyResultMessage("", listOf(IconType.VIDEO, IconType.AUDIO), folderIsEmpty = false, resultIsEmpty = true),
        )
        assertEquals(
            "No video matches \"absent\"",
            emptyResultMessage("absent", listOf(IconType.VIDEO), folderIsEmpty = false, resultIsEmpty = true),
        )
    }

    @Test
    fun `no message when something shows, nothing narrows, or the folder itself is empty`() {
        assertNull(emptyResultMessage("clip", emptyList(), folderIsEmpty = false, resultIsEmpty = false))
        assertNull(emptyResultMessage("", emptyList(), folderIsEmpty = false, resultIsEmpty = true))
        assertNull(emptyResultMessage("absent", emptyList(), folderIsEmpty = true, resultIsEmpty = true))
    }
}
