package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenFilesTest {

    private fun hidden(name: String, parent: String = "gallery-dl") =
        HiddenFiles.isHidden(name, Pairings.join(parent, name))

    @Test
    fun `dot files and dot folders are hidden`() {
        listOf(".DS_Store", ".samples", "._001.jpg", ".hidden").forEach {
            assertTrue(it, hidden(it))
        }
    }

    @Test
    fun `sqlite databases and their sidecars are hidden`() {
        listOf(
            "download-archive.sqlite3",
            "download-archive.sqlite3-journal",
            "cache.sqlite-wal",
            "cache.sqlite-shm",
            "library.db",
            "library.DB3",
        ).forEach { assertTrue(it, hidden(it)) }
    }

    @Test
    fun `windows litter is hidden whatever its case`() {
        listOf("Thumbs.db", "thumbs.db", "desktop.ini", "Desktop.INI").forEach {
            assertTrue(it, hidden(it))
        }
    }

    @Test
    fun `the tag index folder is hidden only at its canonical path`() {
        assertTrue(HiddenFiles.isHidden("_index", "gallery-dl/_index"))
        assertTrue(HiddenFiles.isHidden("_index", "/gallery-dl/_index/"))
        assertFalse(HiddenFiles.isHidden("_index", "other/_index"))
        assertFalse(HiddenFiles.isHidden("_index", "_index"))
    }

    @Test
    fun `ordinary media and folders stay visible`() {
        listOf(
            "a.b.jpg",
            "clip.mp4",
            "pixiv",
            "notes.txt",
            "database-dump.json",
            "journal",
            "_indexes",
            "db",
        ).forEach { assertFalse(it, hidden(it)) }
    }

    @Test
    fun `a paired local copy of the index is named by its share path`() {
        val pairings = Pairings(
            listOf(
                Pairing(
                    localConnectionId = 2,
                    localSubPath = "SDSync",
                    remoteConnectionId = 1,
                    remoteSubPath = "gallery-dl",
                )
            )
        )

        val visible = HiddenFiles.visiblePaths(
            2,
            listOf("SDSync/_index", "SDSync/pixiv", "_index"),
            pairings::canonicalize,
        )

        assertEquals(listOf("SDSync/pixiv", "_index"), visible)
    }
}
