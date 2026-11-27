package com.bespectacledcyborg.remoteexplorer.ui.screens

import com.bespectacledcyborg.remoteexplorer.data.EntrySide
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

    @Test
    fun `an empty side filter says which side it was looking for`() {
        assertEquals(
            "Nothing here is only on the NAS",
            emptyResultMessage("", emptyList(), false, true, EntrySide.NAS_ONLY),
        )
        assertEquals(
            "No image here is only on this tablet",
            emptyResultMessage("", listOf(IconType.IMAGE), false, true, EntrySide.TABLET_ONLY),
        )
        assertEquals(
            "Nothing only on the NAS matches \"clip\"",
            emptyResultMessage("clip", emptyList(), false, true, EntrySide.NAS_ONLY),
        )
    }

    @Test
    fun `the side filter keeps only entries on that side`() {
        val anime = LocalFile(3, "", "Anime", FileType.FOLDER, false, lastModified = Date(0))
        val loose = anime.copy(filename = "loose")
        val both = anime.copy(filename = "Download")
        val sides = mapOf("Anime" to EntrySide.NAS_ONLY, "loose" to EntrySide.TABLET_ONLY)

        assertEquals(listOf(anime), listOf(anime, loose, both).onSide(EntrySide.NAS_ONLY, sides))
        assertEquals(listOf(anime, loose, both), listOf(anime, loose, both).onSide(null, sides))
    }
}
