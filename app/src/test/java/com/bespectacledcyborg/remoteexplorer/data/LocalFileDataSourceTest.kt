package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.utils.Utils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException

/**
 * The local source against a real directory on the JVM.
 *
 * Two things here are load-bearing beyond "it lists files". The root is the only boundary
 * between a Syncthing folder and the whole of shared storage, since the HTTP server hands
 * out whatever this opens; and Syncthing's own files must never be listed or opened, since
 * a temp file thumbnailed under its final name would poison the copy it is paired with.
 */
class LocalFileDataSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: java.io.File
    private val source = LocalFileDataSource(hasAllFilesAccess = { true })

    @Before
    fun setUp() {
        root = folder.newFolder("Sync")
        java.io.File(root, "Photos").mkdir()
        java.io.File(root, "Photos/beach.jpg").writeBytes(ByteArray(100) { it.toByte() })
        java.io.File(root, "notes.txt").writeText("hello")
        source.connect(LocalRootDetails(connectionId = 7, root = root.path))
    }

    @Test
    fun `lists a folder with directories marked the way the repository reads them`() {
        val listing = source.listFiles(7, "").associateBy { it.filename }

        assertEquals(setOf("Photos", "notes.txt"), listing.keys)
        assertTrue(Utils.isDirectory(listing.getValue("Photos").fileAttributes))
        assertFalse(Utils.isDirectory(listing.getValue("notes.txt").fileAttributes))
        assertEquals("", listing.getValue("notes.txt").parentPath)
    }

    @Test
    fun `lists a subfolder by its path under the root`() {
        val listing = source.listFiles(7, "Photos")

        assertEquals(listOf("beach.jpg"), listing.map { it.filename })
        assertEquals("Photos", listing.single().parentPath)
    }

    @Test
    fun `hides every kind of Syncthing file from a listing`() {
        java.io.File(root, ".stfolder").mkdir()
        java.io.File(root, ".stversions").mkdir()
        java.io.File(root, ".stignore").writeText("Private")
        java.io.File(root, "Photos/.syncthing.beach2.jpg.tmp").writeText("half")
        java.io.File(root, "Photos/~syncthing~beach3.jpg.tmp").writeText("half")

        assertEquals(setOf("Photos", "notes.txt"), source.listFiles(7, "").map { it.filename }.toSet())
        assertEquals(listOf("beach.jpg"), source.listFiles(7, "Photos").map { it.filename })
    }

    @Test
    fun `refuses to open a Syncthing file, including anything under one`() {
        java.io.File(root, ".stversions/Photos").mkdirs()
        java.io.File(root, ".stversions/Photos/beach~20260101.jpg").writeText("old")
        java.io.File(root, "Photos/.syncthing.beach2.jpg.tmp").writeText("half")

        assertMissing { source.openFile(7, ".stversions/Photos/beach~20260101.jpg") }
        assertMissing { source.openFile(7, "Photos/.syncthing.beach2.jpg.tmp") }
        assertMissing { source.listFiles(7, ".stversions") }
    }

    @Test
    fun `refuses a path that climbs out of the root`() {
        folder.newFile("secret.txt").writeText("not yours")

        assertMissing { source.openFile(7, "../secret.txt") }
        assertMissing { source.openFile(7, "Photos/../../secret.txt") }
        assertMissing { source.listFiles(7, "..") }
    }

    @Test
    fun `a path that climbs and comes back inside is fine`() {
        source.openFile(7, "Photos/../notes.txt").use { assertEquals(5L, it.length()) }
    }

    @Test
    fun `a missing file is missing, not some other failure`() {
        assertMissing { source.openFile(7, "Photos/nope.jpg") }
        assertMissing { source.openFile(7, "Photos") }
    }

    @Test
    fun `reads at an offset, short at the end and -1 past it`() {
        source.openFile(7, "Photos/beach.jpg").use { file ->
            assertEquals(100L, file.length())

            val buffer = ByteArray(10)
            assertEquals(4, file.read(buffer, 50, 2, 4))
            assertArrayEquals(byteArrayOf(50, 51, 52, 53), buffer.copyOfRange(2, 6))

            assertEquals(4, file.read(buffer, 96, 0, 10))
            assertEquals(-1, file.read(buffer, 100, 0, 10))
        }
    }

    @Test
    fun `streams the whole file however it has been read positionally`() {
        source.openFile(7, "Photos/beach.jpg").use { file ->
            file.read(ByteArray(10), 90, 0, 10)
            assertArrayEquals(ByteArray(100) { it.toByte() }, file.inputStream().readBytes())
        }
    }

    @Test
    fun `filters by the share's wildcard pattern, ignoring case`() {
        java.io.File(root, "Photos/City.JPG").writeText("x")

        assertEquals(
            listOf("City.JPG", "beach.jpg"),
            source.listFiles(7, "Photos", filter = "*.jpg").map { it.filename }.sorted(),
        )
        assertEquals(
            listOf("beach.jpg"),
            source.listFiles(7, "Photos", filter = "b?ach*").map { it.filename },
        )
    }

    @Test
    fun `will not open a root without all files access`() {
        val denied = LocalFileDataSource(hasAllFilesAccess = { false })

        val thrown = runCatching { denied.connect(LocalRootDetails(1, root.path)) }
        assertTrue(thrown.exceptionOrNull() is AllFilesAccessMissing)
    }

    @Test
    fun `a connection that never connected is refused`() {
        val thrown = runCatching { source.listFiles(99, "") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    private fun assertMissing(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected FileNotFoundException, got $thrown", thrown is FileNotFoundException)
    }
}

class SyncthingNamesTest {
    @Test
    fun `recognises Syncthing's own names and nothing else`() {
        listOf(
            ".stfolder", ".stignore", ".stversions",
            ".syncthing.IMG_0001.jpg.tmp", "~syncthing~IMG_0001.jpg.tmp",
        ).forEach { assertTrue(it, isSyncthingInternal(it)) }

        listOf(
            "stfolder", ".stfolder2", "IMG_0001.jpg", ".syncthing.notes",
            "notes.tmp", "my~syncthing~notes.txt",
        ).forEach { assertFalse(it, isSyncthingInternal(it)) }
    }
}
