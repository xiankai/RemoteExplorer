package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.thumbnailCacheKey
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
