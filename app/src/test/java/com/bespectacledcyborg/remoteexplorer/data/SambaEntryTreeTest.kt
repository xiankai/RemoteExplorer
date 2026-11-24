package com.bespectacledcyborg.remoteexplorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException

private const val WRITABLE = 1
private const val READ_ONLY = 2

class SambaEntryTreeTest {
    private val port = System.getenv("REX_SMB_WRITE_PORT")?.toIntOrNull()
    private val smb = SambaFileDataSource()

    private fun details(id: ConnectionId, share: String) =
        ConnectionDetails(id, "127.0.0.1", port!!, "explorer", "explorer", "", share)

    @Before
    fun setUp() {
        assumeTrue("set REX_SMB_WRITE_PORT to a writable throwaway share", port != null)
        smb.connect(details(WRITABLE, "media"))
        smb.connect(details(READ_ONLY, "readonly"))
    }

    @Test
    fun `creates, renames, moves and walks over a real share`() {
        assertTrue(smb.isFolder(WRITABLE, ""))
        assertTrue(smb.isFolder(WRITABLE, "Library/pixiv/gal"))
        assertFalse(smb.isFolder(WRITABLE, "Library/pixiv/file.txt"))
        assertTrue(smb.exists(WRITABLE, "Library/pixiv/file.txt"))
        assertFalse(smb.exists(WRITABLE, "Library/pixiv/nowhere"))

        assertEquals(
            setOf("Library/pixiv/gal/1.jpg", "Library/pixiv/gal/sub/2.jpg"),
            smb.filesUnder(WRITABLE, "Library/pixiv/gal", 100).toSet(),
        )
        assertEquals(1, smb.filesUnder(WRITABLE, "Library/pixiv/gal", 1).size)
        assertEquals(listOf("Library/pixiv/file.txt"), smb.filesUnder(WRITABLE, "Library/pixiv/file.txt", 100))

        smb.createFolder(WRITABLE, "Library/pixiv/new")
        assertTrue(smb.isFolder(WRITABLE, "Library/pixiv/new"))
        assertThrows<FileAlreadyExistsException> { smb.createFolder(WRITABLE, "Library/pixiv/new") }

        smb.move(WRITABLE, "Library/pixiv/gal", "Library/archive/gal2")
        assertTrue(smb.exists(WRITABLE, "Library/archive/gal2/sub/2.jpg"))
        assertFalse(smb.exists(WRITABLE, "Library/pixiv/gal"))

        smb.move(WRITABLE, "Library/archive/gal2", "Library/archive/GAL2")
        assertEquals(listOf("GAL2"), smb.namesIn(WRITABLE, "Library/archive"))

        smb.move(WRITABLE, "Library/pixiv/file.txt", "Library/archive/file.txt")
        assertTrue(smb.exists(WRITABLE, "Library/archive/file.txt"))

        assertThrows<FileAlreadyExistsException> { smb.move(WRITABLE, "Library/pixiv/new", "Library/archive/gal2") }
        assertThrows<java.io.FileNotFoundException> { smb.move(WRITABLE, "Library/pixiv/nowhere", "Library/x") }
    }

    @Test
    fun `a share the account cannot write refuses with access denied`() {
        assertThrows<AccessDeniedException> { smb.createFolder(READ_ONLY, "Library/denied") }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected ${T::class.simpleName}, got $thrown", thrown is T)
    }
}
