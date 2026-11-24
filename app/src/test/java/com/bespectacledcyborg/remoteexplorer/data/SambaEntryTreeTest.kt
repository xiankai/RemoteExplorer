package com.bespectacledcyborg.remoteexplorer.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.CredentialConverters
import com.bespectacledcyborg.remoteexplorer.data.db.FolderSort
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.util.Date

private const val WRITABLE = 1
private const val READ_ONLY = 2
private const val TABLET = 3

@RunWith(RobolectricTestRunner::class)
class SambaEntryTreeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val port = System.getenv("REX_SMB_WRITE_PORT")?.toIntOrNull()
    private val smb = SambaFileDataSource()

    @Test
    fun `a NAS-only folder is renamed through FileOperations on the real share`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
        val root = folder.newFolder("tablet")
        java.io.File(root, "pixiv").mkdirs()
        db.connectionDao().addConnection(
            Connection(
                id = WRITABLE, name = "nas", hostname = "nas", username = "u", password = SecretText("p"),
                domain = "", share = "media", lastModified = Date(0),
            )
        )
        db.connectionDao().addConnection(
            Connection(
                id = TABLET, name = "tablet", type = ConnectionType.LOCAL, hostname = null, username = null,
                password = null, domain = null, share = null, localRoot = root.path, lastModified = Date(0),
            )
        )
        db.folderSortDao().upsert(FolderSort(WRITABLE, "Library/pixiv/e2e", SortType.NAME_DESC))
        val local = LocalFileDataSource(hasAllFilesAccess = { true }).apply { connect(LocalRootDetails(TABLET, root.path)) }
        val moved = mutableListOf<Pair<FileIdentity, FileIdentity>>()
        val operations = FileOperations(
            local = local,
            share = smb,
            isLocalRoot = { it == TABLET },
            ensureOpen = {},
            pairings = { Pairings(listOf(Pairing(TABLET, "", WRITABLE, "Library"))) },
            listedNames = { _, _ -> emptyList() },
            paths = db.pathMoveDao(),
            thumbnails = object : ThumbnailMover {
                override suspend fun move(moves: List<Pair<FileIdentity, FileIdentity>>) {
                    moved += moves
                }
            },
            dispatcher = Dispatchers.Unconfined,
        )

        assertEquals(
            FileOperationResult.Refused(Refusal.NEEDS_NAS_CONFIRMATION),
            operations.rename(TABLET, "pixiv/e2e", "e2e-renamed"),
        )
        assertEquals(
            FileOperationResult.Done(TABLET, "pixiv/e2e-renamed"),
            operations.rename(TABLET, "pixiv/e2e", "e2e-renamed", confirmedOnNas = true),
        )
        assertTrue(smb.exists(WRITABLE, "Library/pixiv/e2e-renamed/1.jpg"))
        assertFalse(java.io.File(root, "pixiv/e2e-renamed").exists())
        assertEquals(listOf("Library/pixiv/e2e-renamed"), db.folderSortDao().observeAll().first().map { it.path })
        assertEquals(
            listOf(FileIdentity(WRITABLE, "Library/pixiv/e2e/1.jpg") to FileIdentity(WRITABLE, "Library/pixiv/e2e-renamed/1.jpg")),
            moved,
        )
        db.close()
    }

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
    fun `a folder with a file held open cannot be renamed`() {
        smb.openFile(WRITABLE, "Library/pixiv/held/1.jpg").use {
            assertThrows<AccessDeniedException> { smb.move(WRITABLE, "Library/pixiv/held", "Library/pixiv/held2") }
        }
        smb.move(WRITABLE, "Library/pixiv/held", "Library/pixiv/held2")
        assertTrue(smb.isFolder(WRITABLE, "Library/pixiv/held2"))
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
