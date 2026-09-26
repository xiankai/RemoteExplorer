package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.DirTagRelation
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.thumbnailCacheKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/**
 * [Pairings.canonicalize] is what makes a local copy and its share one file as far as the
 * caches are concerned, so a wrong answer is silent in both directions: too narrow and the
 * same picture is rendered and stored twice, too wide and one file shows another's thumbnail.
 */
class PairingsTest {

    // Local root 3 holds a copy of share 1's "Library" folder at its own root, and a copy of
    // share 1's "Library/2024" — a more specific pairing — under "Recent".
    private val pairings = Pairings(
        listOf(
            Pairing(localConnectionId = 3, localSubPath = "", remoteConnectionId = 1, remoteSubPath = "Library"),
            Pairing(localConnectionId = 3, localSubPath = "Recent", remoteConnectionId = 1, remoteSubPath = "Library/2024"),
        )
    )

    @Test
    fun `a file in a paired local folder is named as its remote twin`() {
        assertEquals(
            FileIdentity(1, "Library/Photos/beach.jpg"),
            pairings.canonicalize(3, "Photos/beach.jpg"),
        )
    }

    @Test
    fun `the most specific pairing wins`() {
        assertEquals(
            FileIdentity(1, "Library/2024/beach.jpg"),
            pairings.canonicalize(3, "Recent/beach.jpg"),
        )
    }

    @Test
    fun `folders match by segment, not by string prefix`() {
        // "Recent2" starts with "Recent" but is not under it, so the root pairing applies.
        assertEquals(
            FileIdentity(1, "Library/Recent2/a.jpg"),
            pairings.canonicalize(3, "Recent2/a.jpg"),
        )
    }

    @Test
    fun `a paired folder itself maps to the remote folder`() {
        assertEquals(FileIdentity(1, "Library/2024"), pairings.canonicalize(3, "Recent"))
        assertEquals(FileIdentity(1, "Library"), pairings.canonicalize(3, ""))
    }

    @Test
    fun `a share, and anything unpaired, is already canonical`() {
        assertEquals(FileIdentity(1, "Library/a.jpg"), pairings.canonicalize(1, "Library/a.jpg"))
        assertEquals(FileIdentity(9, "a.jpg"), pairings.canonicalize(9, "a.jpg"))
        assertEquals(FileIdentity(9, "a.jpg"), Pairings.NONE.canonicalize(9, "a.jpg"))
    }

    @Test
    fun `a local folder outside every pairing stays local`() {
        val narrow = Pairings(listOf(Pairing(3, "Sync", 1, "")))
        assertEquals(FileIdentity(3, "Other/a.jpg"), narrow.canonicalize(3, "Other/a.jpg"))
    }

    @Test
    fun `canonicalizing is idempotent`() {
        // The client canonicalizes before building a URL and the server again on receipt.
        val once = pairings.canonicalize(3, "Photos/beach.jpg")
        assertEquals(once, pairings.canonicalize(once.connectionId, once.path))
    }

    @Test
    fun `finds the local copies of a remote file, most specific first`() {
        assertEquals(
            listOf(FileIdentity(3, "Recent/beach.jpg"), FileIdentity(3, "2024/beach.jpg")),
            pairings.localCopiesOf(FileIdentity(1, "Library/2024/beach.jpg")),
        )
        assertEquals(emptyList<FileIdentity>(), pairings.localCopiesOf(FileIdentity(1, "Elsewhere/a.jpg")))
    }

    @Test
    fun `a local file and its remote twin share one thumbnail cache entry`() {
        val local = pairings.canonicalize(3, "Photos/beach.jpg")
        val remote = pairings.canonicalize(1, "Library/Photos/beach.jpg")

        assertEquals(
            thumbnailCacheKey(remote.connectionId, remote.path),
            thumbnailCacheKey(local.connectionId, local.path),
        )
    }

    @Test
    fun `a share's row is put in the frame of the paired local root`() {
        assertEquals(
            FileIdentity(3, "Recent/beach.jpg"),
            pairings.inFrameOf(3, FileIdentity(1, "Library/2024/beach.jpg")),
        )
    }

    @Test
    fun `framing is a no-op while browsing the share itself`() {
        // No pairing has a share as its local side, so nothing browsed over SMB changes.
        val row = FileIdentity(1, "Library/2024/beach.jpg")
        assertEquals(row, pairings.inFrameOf(1, row))
    }

    @Test
    fun `a row the current connection holds no copy of keeps its identity`() {
        val elsewhere = FileIdentity(1, "Elsewhere/a.jpg")
        assertEquals(elsewhere, pairings.inFrameOf(3, elsewhere))
        val otherShare = FileIdentity(2, "Library/a.jpg")
        assertEquals(otherShare, pairings.inFrameOf(3, otherShare))
    }

    // A local root at the share's Photos folder: the case the root-to-root pairing hides,
    // where the local path and the remote one actually differ.
    private val photos = Pairings(listOf(Pairing(3, "", 1, "Photos")))

    private fun row(parentPath: String, filename: String, type: FileType = FileType.FILE) =
        LocalFile(
            connectionId = 1,
            parentPath = parentPath,
            filename = filename,
            type = type,
            canHaveThumbnail = true,
            lastModified = Date(0),
        )

    @Test
    fun `a tagged file row is re-expressed as the local copy's row`() {
        val framed = photos.inFrameOf(3, listOf(row("Photos/2024", "beach.jpg"))).single()

        assertEquals(3, framed.connectionId)
        assertEquals("2024", framed.parentPath)
        assertEquals("beach.jpg", framed.filename)
    }

    @Test
    fun `a tagged row at the local root gets an empty parent`() {
        val framed = photos.inFrameOf(3, listOf(row("Photos", "beach.jpg"))).single()

        assertEquals("", framed.parentPath)
        assertEquals("beach.jpg", framed.fullPath())
    }

    @Test
    fun `a tagged gallery row is re-expressed too`() {
        val gallery = DirTagRelation(connectionId = 1, parentPath = "Photos/trip", tag = "x").asFolder()
        val framed = photos.inFrameOf(3, listOf(gallery)).single()

        assertEquals(FileType.FOLDER, framed.type)
        assertEquals(3, framed.connectionId)
        assertEquals("trip", framed.fullPath())
    }

    @Test
    fun `the paired folder itself keeps its own name rather than becoming nameless`() {
        val gallery = DirTagRelation(connectionId = 1, parentPath = "Photos", tag = "x").asFolder()
        assertEquals(gallery, photos.inFrameOf(3, listOf(gallery)).single())
    }

    @Test
    fun `a framed row canonicalizes back to the row it came from`() {
        // Which is what makes its URL, and so its thumbnail, the share's.
        val original = row("Photos/2024", "beach.jpg")
        val framed = photos.inFrameOf(3, listOf(original)).single()

        assertEquals(
            FileIdentity(1, original.fullPath()),
            photos.canonicalize(framed.connectionId, framed.fullPath()),
        )
    }

    @Test
    fun `a folder typed with slashes is stored the way paths are spelled`() {
        // The pairing dialog takes free text; every path in the app has no leading or
        // trailing slash, and an empty one is the root.
        assertEquals("Photos", Pairings.normalize("/Photos/"))
        assertEquals("Photos/2024", Pairings.normalize("Photos/2024/"))
        assertEquals("", Pairings.normalize("/"))
    }
}
