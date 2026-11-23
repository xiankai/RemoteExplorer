package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import java.nio.file.Files
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionListTest {
    private val nas = share(id = 1, name = "NAS")
    private val laptop = share(id = 2, name = "Laptop")
    private val sdSyncthing = localRoot(id = 3, name = "SDSyncthing")
    private val phone = localRoot(id = 4, name = "Phone")

    private val everything = listOf(nas, laptop, sdSyncthing, phone)
    private val allPresent: (Connection) -> Boolean = { true }

    @Test
    fun aShareWithAPresentCopyIsShownOnlyAsItsLocalRoot() {
        val list = ConnectionList(everything, listOf(pairing(sdSyncthing, "", nas, "")), allPresent)

        assertEquals(listOf(laptop, sdSyncthing, phone), list.shown())
    }

    @Test
    fun aShareComesBackWhenItsLocalRootIsGone() {
        val list = ConnectionList(
            everything,
            listOf(pairing(sdSyncthing, "", nas, "")),
            isPresent = { it != sdSyncthing },
        )

        assertEquals(everything, list.shown())
    }

    @Test
    fun aShareCopiedOnlyInPartIsStillHiddenBehindItsCopy() {
        val list = ConnectionList(
            everything,
            listOf(pairing(sdSyncthing, "gallery-dl", nas, "gallery-dl")),
            allPresent,
        )

        assertEquals(listOf(laptop, sdSyncthing, phone), list.shown())
    }

    @Test
    fun aShareCopiedToTwoRootsStaysHiddenWhileEitherIsPresent() {
        val pairings = listOf(pairing(sdSyncthing, "", nas, ""), pairing(phone, "NAS", nas, ""))

        assertEquals(
            listOf(laptop, sdSyncthing, phone),
            ConnectionList(everything, pairings, isPresent = { it != sdSyncthing }).shown(),
        )
        assertEquals(
            everything,
            ConnectionList(everything, pairings, isPresent = { it.type == ConnectionType.SMB }).shown(),
        )
    }

    @Test
    fun nothingIsHiddenWithoutPairings() {
        assertEquals(everything, ConnectionList(everything, emptyList(), allPresent).shown())
    }

    @Test
    fun aLocalRootIsNeverHiddenEvenWhenSomethingClaimsToCopyIt() {
        val list = ConnectionList(everything, listOf(pairing(phone, "", sdSyncthing, "")), allPresent)

        assertEquals(everything, list.shown())
    }

    @Test
    fun restoringAHiddenShareOpensItsLocalCopyOfTheSameFolder() {
        val list = ConnectionList(
            everything,
            listOf(pairing(sdSyncthing, "Sync/NAS", nas, "")),
            allPresent,
        )

        assertEquals(
            Location(sdSyncthing, "Sync/NAS/gallery-dl/artist"),
            list.entryFor(Location(nas, "gallery-dl/artist")),
        )
        assertEquals(Location(sdSyncthing, "Sync/NAS"), list.entryFor(Location(nas, "")))
    }

    @Test
    fun restoringPrefersTheMostSpecificPresentCopy() {
        val list = ConnectionList(
            everything,
            listOf(
                pairing(sdSyncthing, "", nas, ""),
                pairing(phone, "pixiv", nas, "gallery-dl/pixiv"),
            ),
            allPresent,
        )

        assertEquals(
            Location(phone, "pixiv/artist"),
            list.entryFor(Location(nas, "gallery-dl/pixiv/artist")),
        )
    }

    @Test
    fun restoringOutsideAPartialCopyStaysOnTheShare() {
        val list = ConnectionList(
            everything,
            listOf(pairing(sdSyncthing, "gallery-dl", nas, "gallery-dl")),
            allPresent,
        )

        assertEquals(Location(nas, "Music"), list.entryFor(Location(nas, "Music")))
        assertEquals(
            Location(sdSyncthing, "gallery-dl/pixiv"),
            list.entryFor(Location(nas, "gallery-dl/pixiv")),
        )
    }

    @Test
    fun restoringAShownConnectionLeavesItAlone() {
        val list = ConnectionList(
            everything,
            listOf(pairing(sdSyncthing, "", nas, "")),
            isPresent = { it != sdSyncthing },
        )

        assertEquals(Location(nas, "gallery-dl"), list.entryFor(Location(nas, "gallery-dl")))
        assertEquals(Location(laptop, "x"), list.entryFor(Location(laptop, "x")))
    }

    @Test
    fun aLocalRootIsPresentOnlyWhileItsFolderIsThere() {
        val folder = Files.createTempDirectory("connection-list").toFile()
        val root = sdSyncthing.copy(localRoot = folder.absolutePath)

        assertTrue(ConnectionList.isReadableLocalRoot(root))
        folder.delete()
        assertFalse(ConnectionList.isReadableLocalRoot(root))
        assertFalse(ConnectionList.isReadableLocalRoot(nas))
    }

    private fun share(id: Int, name: String) = Connection(
        id = id,
        name = name,
        hostname = "host",
        username = null,
        password = null,
        domain = null,
        share = "share",
        lastModified = Date(0),
    )

    private fun localRoot(id: Int, name: String) = Connection(
        id = id,
        name = name,
        type = ConnectionType.LOCAL,
        hostname = null,
        username = null,
        password = null,
        domain = null,
        share = null,
        localRoot = "/storage/$name",
        lastModified = Date(0),
    )

    private fun pairing(local: Connection, localSubPath: String, remote: Connection, remoteSubPath: String) =
        Pairing(local.id, localSubPath, remote.id, remoteSubPath)
}
