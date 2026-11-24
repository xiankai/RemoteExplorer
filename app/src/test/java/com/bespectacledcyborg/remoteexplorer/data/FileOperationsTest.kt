package com.bespectacledcyborg.remoteexplorer.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.data.db.CredentialConverters
import com.bespectacledcyborg.remoteexplorer.data.db.DirTagRelation
import com.bespectacledcyborg.remoteexplorer.data.db.DirectorySync
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.FolderCoverChoice
import com.bespectacledcyborg.remoteexplorer.data.db.FolderSort
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import com.bespectacledcyborg.remoteexplorer.data.views.ViewRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

private const val SHARE = 1
private const val PHONE = 3

@RunWith(RobolectricTestRunner::class)
class FileOperationsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var root: java.io.File
    private lateinit var nasRoot: java.io.File
    private val local = LocalFileDataSource(hasAllFilesAccess = { true })
    private val nasDisk = LocalFileDataSource(hasAllFilesAccess = { true })
    private var nasMoveFailure: Exception? = null
    private val nas = object : EntryTree by nasDisk {
        override fun move(connectionId: ConnectionId, from: String, to: String) {
            nasMoveFailure?.let { throw it }
            nasDisk.move(connectionId, from, to)
        }
    }
    private var pairings = Pairings(listOf(Pairing(PHONE, "", SHARE, "Library")))
    private val thumbnailMoves = mutableListOf<Pair<FileIdentity, FileIdentity>>()
    private var localOpens: () -> Unit = {}
    private var nasOpens: () -> Unit = {}

    private val operations by lazy {
        FileOperations(
            local = local,
            share = nas,
            isLocalRoot = { it == PHONE },
            ensureOpen = { if (it == PHONE) localOpens() else nasOpens() },
            pairings = { pairings },
            listedNames = { id, path -> db.localDatabaseDao().getFiles(id, path).map { it.filename } },
            paths = db.pathMoveDao(),
            thumbnails = object : ThumbnailMover {
                override suspend fun move(moves: List<Pair<FileIdentity, FileIdentity>>) {
                    thumbnailMoves += moves
                }
            },
            dispatcher = Dispatchers.Unconfined,
            clock = { 42L },
        )
    }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
        root = folder.newFolder("Sync")
        db.connectionDao().addConnection(
            Connection(
                id = SHARE, name = "nas", hostname = "nas", username = "u", password = SecretText("p"),
                domain = "", share = "media", lastModified = Date(0),
            )
        )
        db.connectionDao().addConnection(
            Connection(
                id = PHONE, name = "phone", type = ConnectionType.LOCAL, hostname = null, username = null,
                password = null, domain = null, share = null, localRoot = root.path, lastModified = Date(0),
            )
        )
        db.settingsDao().insert(SettingsEntity())
        local.connect(LocalRootDetails(PHONE, root.path))
        nasRoot = folder.newFolder("nas")
        nasDisk.connect(LocalRootDetails(SHARE, nasRoot.path))
        listOf("pixiv/100_%a/1.jpg", "pixiv/100_%a/2.jpg", "pixiv/100_%a2/1.jpg").forEach {
            write(it)
            writeOnNas("Library/$it")
        }
        java.io.File(root, "archive").mkdirs()
        java.io.File(nasRoot, "Library/archive").mkdirs()
    }

    @After
    fun tearDown() = db.close()

    private fun write(path: String) {
        java.io.File(root, path).apply { parentFile!!.mkdirs() }.writeText(path)
    }

    private fun writeOnNas(path: String) {
        java.io.File(nasRoot, path).apply { parentFile!!.mkdirs() }.writeText(path)
    }

    private fun rows(sql: String): List<String> {
        val cursor = db.openHelper.readableDatabase.query(sql)
        return buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) })
        }.also { cursor.close() }
    }

    private fun listed(connectionId: Int, parentPath: String, name: String, type: FileType = FileType.FOLDER) =
        LocalFile(connectionId, parentPath, name, type, false, lastModified = Date(0))

    @Test
    fun `renaming a gallery moves it on disk and carries its history, sort, cover and tags`() = runTest {
        db.fileViewDao().recordOpen(SHARE, "Library/pixiv/100_%a", "1.jpg", at = 5, day = 1)
        db.fileViewDao().recordOpen(SHARE, "Library/pixiv/100_%a2", "1.jpg", at = 5, day = 1)
        db.folderSortDao().upsert(FolderSort(SHARE, "Library/pixiv/100_%a", SortType.NAME_DESC))
        db.folderCoverChoiceDao().set(FolderCoverChoice(SHARE, "Library/pixiv/100_%a", CoverMode.NEWEST, 0))
        db.localDatabaseDao().addDirTagRelation(DirTagRelation(SHARE, "Library/pixiv/100_%a", "cat"))
        db.localDatabaseDao().addDirTagRelation(DirTagRelation(SHARE, "Library/pixiv/100_%a2", "dog"))

        val result = operations.rename(PHONE, "pixiv/100_%a", "100_b")

        assertEquals(FileOperationResult.Done(PHONE, "pixiv/100_b"), result)
        assertTrue(java.io.File(root, "pixiv/100_b/1.jpg").isFile)
        assertFalse(java.io.File(root, "pixiv/100_%a").exists())
        assertEquals(
            listOf("Library/pixiv/100_%a2|1.jpg", "Library/pixiv/100_b|1.jpg"),
            rows("SELECT parentPath, filename FROM FileView ORDER BY parentPath"),
        )
        assertEquals(listOf("Library/pixiv/100_b"), rows("SELECT path FROM FolderSort"))
        assertEquals(listOf("Library/pixiv/100_b"), rows("SELECT path FROM FolderCoverChoice"))
        assertEquals(
            listOf("Library/pixiv/100_%a2|dog", "Library/pixiv/100_b|cat"),
            rows("SELECT parentPath, tag FROM DirTagRelation ORDER BY parentPath"),
        )
    }

    @Test
    fun `a paired move leaves a pending record and renames the cached thumbnails by canonical path`() = runTest {
        val result = operations.move(PHONE, "pixiv/100_%a", "archive")

        assertEquals(FileOperationResult.Done(PHONE, "archive/100_%a"), result)
        assertEquals(listOf("1|Library/pixiv|100_%a|Library/archive/100_%a|42"), rows("SELECT * FROM PendingMove"))
        assertEquals(
            setOf(
                FileIdentity(SHARE, "Library/pixiv/100_%a/1.jpg") to FileIdentity(SHARE, "Library/archive/100_%a/1.jpg"),
                FileIdentity(SHARE, "Library/pixiv/100_%a/2.jpg") to FileIdentity(SHARE, "Library/archive/100_%a/2.jpg"),
            ),
            thumbnailMoves.toSet(),
        )
    }

    @Test
    fun `history and covers written by their real writers read back at the new path`() = runTest {
        val views = ViewRecorder(db.fileViewDao(), settings = { db.settingsDao().getSettingsOnce() }, clock = { 7L })
        val covers = CoverPreferences(
            choices = db.folderCoverChoiceDao(),
            shuffleSetting = { com.bespectacledcyborg.remoteexplorer.data.db.CoverShuffle.MARKED },
            firstPageFolders = { emptyList() },
            pairings = { pairings },
            directoryTags = { _, _ -> emptyList() },
            aliasCanonicals = { _, _ -> emptyList() },
            hasSubfolders = { _, _ -> false },
        )
        views.recordOpen(pairings.canonicalize(PHONE, "pixiv/100_%a/1.jpg"))
        covers.choose(PHONE, "pixiv/100_%a", CoverMode.NEWEST)

        operations.move(PHONE, "pixiv/100_%a", "archive")

        val moved = pairings.canonicalize(PHONE, "archive/100_%a")
        assertEquals(1, db.fileViewDao().get(moved.connectionId, moved.path, "1.jpg")?.openCount)
        assertEquals(CoverMode.NEWEST, covers.choiceFor(PHONE, "archive/100_%a"))
    }

    @Test
    fun `an unreachable local root is not mistaken for a NAS-only entry`() = runTest {
        localOpens = { throw AllFilesAccessMissing() }

        assertEquals(FileOperationResult.Refused(Refusal.LOCAL_UNAVAILABLE), operations.rename(PHONE, "pixiv/100_%a", "b"))
        assertEquals(FileOperationResult.Refused(Refusal.LOCAL_UNAVAILABLE), operations.createFolder(PHONE, "pixiv", "b"))
    }

    @Test
    fun `an entry whose existing name would not be accepted today can still be moved`() = runTest {
        java.io.File(root, "pixiv/what?").mkdirs()

        assertEquals(FileOperationResult.Done(PHONE, "archive/what?"), operations.move(PHONE, "pixiv/what?", "archive"))
    }

    @Test
    fun `a pending move settles once the share stops listing the old name, or after two weeks`() = runTest {
        operations.move(PHONE, "pixiv/100_%a", "archive")
        val pending = StoredPendingMoves(db.pathMoveDao())
        val folder = FileIdentity(SHARE, "Library/pixiv")

        pending.settle(folder, setOf("100_%a", "100_%a2"), now = 43L)
        assertEquals(setOf("100_%a"), pending.namesIn(folder))

        pending.settle(folder, setOf("100_%a", "100_%a2"), now = 42L + PENDING_MOVE_TTL_MILLIS + 1)
        assertEquals(emptySet<String>(), pending.namesIn(folder))

        operations.move(PHONE, "archive/100_%a", "pixiv")
        operations.move(PHONE, "pixiv/100_%a", "archive")
        pending.settle(folder, setOf("100_%a2"), now = 43L)
        assertEquals(emptySet<String>(), pending.namesIn(folder))
    }

    @Test
    fun `an unpaired root moves its own records and leaves nothing pending`() = runTest {
        pairings = Pairings.NONE
        db.folderSortDao().upsert(FolderSort(PHONE, "pixiv/100_%a", SortType.NAME_DESC))

        operations.move(PHONE, "pixiv/100_%a", "archive")

        assertEquals(listOf("archive/100_%a"), rows("SELECT path FROM FolderSort"))
        assertEquals(emptyList<String>(), rows("SELECT * FROM PendingMove"))
    }

    @Test
    fun `the listing cache forgets the moved entry and both parents`() = runTest {
        val dao = db.localDatabaseDao()
        dao.insertNewFiles(listOf(listed(PHONE, "pixiv", "100_%a"), listed(PHONE, "pixiv", "100_%a2")))
        dao.insertNewFiles(listOf(listed(PHONE, "pixiv/100_%a", "1.jpg", FileType.FILE)))
        listOf("pixiv", "archive", "pixiv/100_%a", "pixiv/100_%a2").forEach {
            dao.upsertDirectorySync(DirectorySync(PHONE, it, Date(0)))
        }

        operations.move(PHONE, "pixiv/100_%a", "archive")

        assertEquals(listOf("pixiv|100_%a2"), rows("SELECT parentPath, filename FROM LocalFile"))
        assertEquals(listOf("pixiv/100_%a2"), rows("SELECT parentPath FROM DirectorySync"))
    }

    @Test
    fun `a NAS-only entry is renamed on the NAS only once confirmed, and its records follow`() = runTest {
        writeOnNas("Library/pixiv/nas-only/1.jpg")
        db.localDatabaseDao().insertNewFiles(listOf(listed(PHONE, "pixiv", "nas-only")))
        db.fileViewDao().recordOpen(SHARE, "Library/pixiv/nas-only", "1.jpg", at = 5, day = 1)
        db.folderSortDao().upsert(FolderSort(SHARE, "Library/pixiv/nas-only", SortType.NAME_DESC))

        assertEquals(
            FileOperationResult.Refused(Refusal.NEEDS_NAS_CONFIRMATION),
            operations.rename(PHONE, "pixiv/nas-only", "renamed"),
        )
        assertTrue(java.io.File(nasRoot, "Library/pixiv/nas-only").isDirectory)

        val result = operations.rename(PHONE, "pixiv/nas-only", "renamed", confirmedOnNas = true)

        assertEquals(FileOperationResult.Done(PHONE, "pixiv/renamed"), result)
        assertTrue(java.io.File(nasRoot, "Library/pixiv/renamed/1.jpg").isFile)
        assertFalse(java.io.File(nasRoot, "Library/pixiv/nas-only").exists())
        assertFalse(java.io.File(root, "pixiv/renamed").exists())
        assertEquals(listOf("Library/pixiv/renamed|1.jpg"), rows("SELECT parentPath, filename FROM FileView"))
        assertEquals(listOf("Library/pixiv/renamed"), rows("SELECT path FROM FolderSort"))
        assertEquals(emptyList<String>(), rows("SELECT * FROM PendingMove"))
        assertEquals(emptyList<String>(), rows("SELECT filename FROM LocalFile WHERE filename = 'nas-only'"))
        assertEquals(
            listOf(FileIdentity(SHARE, "Library/pixiv/nas-only/1.jpg") to FileIdentity(SHARE, "Library/pixiv/renamed/1.jpg")),
            thumbnailMoves,
        )
    }

    @Test
    fun `browsing the share, a NAS-only entry is moved on the NAS`() = runTest {
        writeOnNas("Library/pixiv/nas-only/1.jpg")

        val result = operations.move(SHARE, "Library/pixiv/nas-only", "Library/archive", confirmedOnNas = true)

        assertEquals(FileOperationResult.Done(SHARE, "Library/archive/nas-only"), result)
        assertTrue(java.io.File(nasRoot, "Library/archive/nas-only/1.jpg").isFile)
    }

    @Test
    fun `a new folder in a NAS-only folder is made on the NAS once confirmed`() = runTest {
        java.io.File(nasRoot, "Library/nas-folder").mkdirs()

        assertEquals(
            FileOperationResult.Refused(Refusal.NEEDS_NAS_CONFIRMATION),
            operations.createFolder(PHONE, "nas-folder", "new"),
        )
        assertEquals(
            FileOperationResult.Done(PHONE, "nas-folder/new"),
            operations.createFolder(PHONE, "nas-folder", "new", confirmedOnNas = true),
        )
        assertTrue(java.io.File(nasRoot, "Library/nas-folder/new").isDirectory)
        assertFalse(java.io.File(root, "nas-folder").exists())
    }

    @Test
    fun `a move between a local entry and a NAS-only folder, either way, is refused`() = runTest {
        writeOnNas("Library/pixiv/nas-only/1.jpg")
        java.io.File(nasRoot, "Library/nas-folder").mkdirs()
        java.io.File(root, "local-only").mkdirs()

        assertEquals(
            FileOperationResult.Refused(Refusal.CROSSES_SIDES),
            operations.move(PHONE, "pixiv/100_%a", "nas-folder", confirmedOnNas = true),
        )
        assertEquals(
            FileOperationResult.Refused(Refusal.CROSSES_SIDES),
            operations.move(PHONE, "pixiv/nas-only", "local-only", confirmedOnNas = true),
        )
        assertTrue(java.io.File(root, "pixiv/100_%a").isDirectory)
        assertTrue(java.io.File(nasRoot, "Library/pixiv/nas-only").isDirectory)
    }

    @Test
    fun `an entry on neither side is not found`() = runTest {
        assertEquals(
            FileOperationResult.Refused(Refusal.NOT_FOUND),
            operations.rename(PHONE, "pixiv/nowhere", "x", confirmedOnNas = true),
        )
    }

    @Test
    fun `a NAS name the local copy of the folder already has is taken, whatever its case`() = runTest {
        writeOnNas("Library/pixiv/nas-only/1.jpg")
        writeOnNas("Library/pixiv/Other/1.jpg")
        write("pixiv/Local-Only/1.jpg")

        assertEquals(
            FileOperationResult.Refused(Refusal.NAME_TAKEN),
            operations.rename(PHONE, "pixiv/nas-only", "OTHER", confirmedOnNas = true),
        )
        assertEquals(
            FileOperationResult.Refused(Refusal.NAME_TAKEN),
            operations.rename(PHONE, "pixiv/nas-only", "local-only", confirmedOnNas = true),
        )
    }

    @Test
    fun `the old name of a move Syncthing has not carried yet is not renamed again on the NAS`() = runTest {
        operations.move(PHONE, "pixiv/100_%a", "archive")

        assertEquals(
            FileOperationResult.Refused(Refusal.SYNC_PENDING),
            operations.rename(PHONE, "pixiv/100_%a", "x", confirmedOnNas = true),
        )
        assertTrue(java.io.File(nasRoot, "Library/pixiv/100_%a").isDirectory)
    }

    @Test
    fun `a NAS folder that holds a paired folder, or a move across a pairing on the NAS, is refused`() = runTest {
        pairings = Pairings(listOf(Pairing(PHONE, "", SHARE, "Library/pixiv")))
        writeOnNas("outside/x/1.jpg")

        assertEquals(
            FileOperationResult.Refused(Refusal.HOLDS_PAIRED_FOLDER),
            operations.rename(SHARE, "Library", "Lib", confirmedOnNas = true),
        )
        assertEquals(
            FileOperationResult.Refused(Refusal.CROSSES_PAIRING),
            operations.move(SHARE, "outside/x", "Library/pixiv", confirmedOnNas = true),
        )
        assertTrue(java.io.File(nasRoot, "Library").isDirectory)
        assertTrue(java.io.File(nasRoot, "outside/x").isDirectory)
    }

    @Test
    fun `a NAS write the share refuses or cannot reach leaves the records alone`() = runTest {
        writeOnNas("Library/pixiv/nas-only/1.jpg")
        db.folderSortDao().upsert(FolderSort(SHARE, "Library/pixiv/nas-only", SortType.NAME_DESC))

        nasMoveFailure = java.nio.file.AccessDeniedException("Library/pixiv/nas-only")
        assertEquals(
            FileOperationResult.Refused(Refusal.NAS_ACCESS_DENIED),
            operations.rename(PHONE, "pixiv/nas-only", "renamed", confirmedOnNas = true),
        )
        nasMoveFailure = java.io.IOException("connection reset")
        assertEquals(
            FileOperationResult.Refused(Refusal.NAS_UNAVAILABLE),
            operations.rename(PHONE, "pixiv/nas-only", "renamed", confirmedOnNas = true),
        )
        nasMoveFailure = null
        nasOpens = { throw java.io.IOException("unreachable") }
        assertEquals(
            FileOperationResult.Refused(Refusal.NAS_UNAVAILABLE),
            operations.rename(PHONE, "pixiv/nas-only", "renamed", confirmedOnNas = true),
        )
        assertEquals(listOf("Library/pixiv/nas-only"), rows("SELECT path FROM FolderSort"))
        assertTrue(thumbnailMoves.isEmpty())
    }

    @Test
    fun `an unreachable local root is never written through the NAS instead`() = runTest {
        localOpens = { throw AllFilesAccessMissing() }

        assertEquals(
            FileOperationResult.Refused(Refusal.LOCAL_UNAVAILABLE),
            operations.rename(PHONE, "pixiv/100_%a", "b", confirmedOnNas = true),
        )
        assertTrue(java.io.File(nasRoot, "Library/pixiv/100_%a").isDirectory)
    }

    @Test
    fun `a name taken on either side is refused, whatever its case`() = runTest {
        db.localDatabaseDao().insertNewFiles(listOf(listed(PHONE, "pixiv", "Nas-Only")))

        assertEquals(FileOperationResult.Refused(Refusal.NAME_TAKEN), operations.rename(PHONE, "pixiv/100_%a", "NAS-ONLY"))
        assertEquals(FileOperationResult.Refused(Refusal.NAME_TAKEN), operations.rename(PHONE, "pixiv/100_%a", "100_%A2"))
        assertEquals(FileOperationResult.Refused(Refusal.NAME_TAKEN), operations.createFolder(PHONE, "", "PIXIV"))
    }

    @Test
    fun `a case-only rename of the same entry goes through`() = runTest {
        val result = operations.rename(PHONE, "pixiv/100_%a", "100_%A")

        assertEquals(FileOperationResult.Done(PHONE, "pixiv/100_%A"), result)
        assertEquals(listOf("100_%A", "100_%a2"), java.io.File(root, "pixiv").list()!!.sorted())
    }

    @Test
    fun `a folder cannot move into itself`() = runTest {
        assertEquals(FileOperationResult.Refused(Refusal.INTO_ITSELF), operations.move(PHONE, "pixiv", "pixiv/100_%a"))
        assertEquals(FileOperationResult.Refused(Refusal.INTO_ITSELF), operations.move(PHONE, "pixiv", "pixiv"))
    }

    @Test
    fun `moving out of a paired folder, or the paired folder itself, is refused`() = runTest {
        pairings = Pairings(listOf(Pairing(PHONE, "pixiv", SHARE, "Library/pixiv")))

        assertEquals(FileOperationResult.Refused(Refusal.CROSSES_PAIRING), operations.move(PHONE, "pixiv/100_%a", "archive"))
        assertEquals(FileOperationResult.Refused(Refusal.HOLDS_PAIRED_FOLDER), operations.rename(PHONE, "pixiv", "p"))
        assertTrue(java.io.File(root, "pixiv/100_%a").isDirectory)
    }

    @Test
    fun `names the NAS or the app would mishandle are refused`() = runTest {
        listOf("", " ", "a/b", "a\\b", "a:b", "what?", ".hidden", "trailing.", "trailing ", ".stversions", "~syncthing~x")
            .forEach { name ->
                assertEquals(name, FileOperationResult.Refused(Refusal.INVALID_NAME), operations.createFolder(PHONE, "", name))
            }
    }

    @Test
    fun `browsing the share, an entry with a local copy is changed on the copy`() = runTest {
        val result = operations.rename(SHARE, "Library/pixiv/100_%a", "100_b")

        assertEquals(FileOperationResult.Done(SHARE, "Library/pixiv/100_b"), result)
        assertTrue(java.io.File(root, "pixiv/100_b").isDirectory)
    }

    @Test
    fun `a new folder is made on the local copy and its parent re-listed`() = runTest {
        db.localDatabaseDao().upsertDirectorySync(DirectorySync(PHONE, "pixiv", Date(0)))

        assertEquals(FileOperationResult.Done(PHONE, "pixiv/new"), operations.createFolder(PHONE, "pixiv", "new"))
        assertTrue(java.io.File(root, "pixiv/new").isDirectory)
        assertEquals(emptyList<String>(), rows("SELECT parentPath FROM DirectorySync"))
    }

    @Test
    fun `history already at the destination is replaced rather than colliding`() = runTest {
        db.fileViewDao().recordOpen(SHARE, "Library/pixiv/100_%a", "1.jpg", at = 5, day = 1)
        db.fileViewDao().recordOpen(SHARE, "Library/pixiv/100_b", "1.jpg", at = 9, day = 2)

        operations.rename(PHONE, "pixiv/100_%a", "100_b")

        assertEquals(listOf("Library/pixiv/100_b|1.jpg|5"), rows("SELECT parentPath, filename, lastOpenedAt FROM FileView"))
    }

    @Test
    fun `first-page folders and the size warning follow the entry`() = runTest {
        db.settingsDao().setFirstPageFolders("Library/pixiv/100_%a\nLibrary/pixiv/100_%a2")

        operations.rename(PHONE, "pixiv/100_%a", "100_b")

        assertEquals(listOf("Library/pixiv/100_b\nLibrary/pixiv/100_%a2"), rows("SELECT firstPageFolders FROM settings"))
        val size = operations.sizeOf(PHONE, "pixiv")!!
        assertEquals(3, size.files)
        assertFalse(size.isLarge())
    }
}
