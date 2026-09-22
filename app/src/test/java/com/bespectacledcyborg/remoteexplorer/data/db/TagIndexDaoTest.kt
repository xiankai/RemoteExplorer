package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

/**
 * Tags that belong to a gallery rather than to a file, against a real database.
 *
 * The queries are the point: a union that double-counts, or a count that multiplies one
 * relation by the other, is invisible in a unit test of the code around it and obvious here.
 */
@RunWith(RobolectricTestRunner::class)
class TagIndexDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LocalDatabaseDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
        dao = db.localDatabaseDao()
    }

    @After
    fun tearDown() = db.close()

    private fun file(parentPath: String, filename: String) = LocalFile(
        connectionId = 1,
        parentPath = parentPath,
        filename = filename,
        type = FileType.FILE,
        canHaveThumbnail = true,
        lastModified = Date(0),
    )

    /**
     * Seeds a listing. Grouped by directory because `reconcileDirectory` replaces a
     * directory's contents — calling it once per file leaves only the last one.
     */
    private fun given(vararg files: LocalFile) =
        files.groupBy { it.parentPath }.forEach { (parentPath, inDirectory) ->
            dao.reconcileDirectory(1, parentPath, inDirectory, Date(0))
        }

    @Test
    fun `a directory tag reaches the files inside it`() {
        given(file("g/gallery", "001.jpg"), file("g/gallery", "002.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("artist:someone"), fileCount = 2, deleted = false)

        val found = dao.getFilesWithTag("artist:someone")

        assertEquals(listOf("001.jpg", "002.jpg"), found.map { it.filename }.sorted())
    }

    /**
     * "Directly inside" is exact. A gallery's tags must not leak into a subdirectory, or a
     * tag on a top-level folder would claim every file on the share beneath it.
     */
    @Test
    fun `a directory tag does not reach deeper`() {
        given(file("g/gallery", "001.jpg"), file("g/gallery/sub", "deep.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("t"), fileCount = 1, deleted = false)

        assertEquals(listOf("001.jpg"), dao.getFilesWithTag("t").map { it.filename })
    }

    /**
     * A file reachable both ways appears once. Both relations are real and can name the same
     * file — a gallery tagged `artist:foo` whose own sidecar says `artist:foo` too.
     */
    @Test
    fun `a file tagged both ways appears once`() {
        given(file("g/gallery", "001.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("shared"), fileCount = 1, deleted = false)
        dao.applyFileTags(1, "g/gallery", "001.jpg", listOf("shared"), deleted = false)

        assertEquals(1, dao.getFilesWithTag("shared").size)
    }

    /**
     * The count adds the two relations rather than joining them. Joined, a tag on three
     * files inside a gallery already carrying it reports nine.
     */
    @Test
    fun `counts add the two relations instead of multiplying them`() {
        given(file("g/gallery", "001.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("t"), fileCount = 20, deleted = false)
        dao.applyFileTags(1, "elsewhere", "loose.jpg", listOf("t"), deleted = false)

        assertEquals(21, dao.getTags().single { it.tag == "t" }.count)
    }

    /**
     * The stored file count is what a directory tag is counted by, not how many rows
     * LocalFile happens to have — which is the whole point of covering directories nobody
     * has browsed into.
     */
    @Test
    fun `a directory counts its files even when none have been listed`() {
        dao.applyDirectoryTags(1, "g/unvisited", listOf("t"), fileCount = 19, deleted = false)

        assertEquals(19, dao.getTags().single { it.tag == "t" }.count)
        // No rows in the listing cache for a gallery nobody has opened, which is why the
        // directory itself has to be offered — see getDirectoriesWithTag.
        assertTrue(dao.getFilesWithTag("t").isEmpty())
    }

    /**
     * The gallery is findable even with nothing cached inside it. Without this a tag would
     * announce nineteen files and then show an empty screen, which is the under-reporting
     * the index exists to remove, moved up one level.
     */
    @Test
    fun `a tagged directory is findable before anyone browses it`() {
        dao.applyDirectoryTags(1, "g/unvisited", listOf("t"), fileCount = 19, deleted = false)

        val found = dao.getDirectoriesWithTag("t")

        assertEquals(listOf("g/unvisited"), found.map { it.parentPath })
        assertEquals(19, found.single().fileCount)
    }

    @Test
    fun `a tagged directory renders as a folder in its parent`() {
        val folder = TaggedDirectory(1, "gallery-dl/pixiv/[137128361] 羽衣狐", 2).asFolder()

        assertEquals("gallery-dl/pixiv", folder.parentPath)
        assertEquals("[137128361] 羽衣狐", folder.filename)
        assertEquals(FileType.FOLDER, folder.type)
        assertEquals("gallery-dl/pixiv/[137128361] 羽衣狐", folder.fullPath())
    }

    /**
     * A re-appended record replaces rather than adds. The log is append-only and the last
     * record for a path wins, so accumulating would keep every tag a gallery ever had and a
     * tag removed upstream would never disappear.
     */
    @Test
    fun `re-applying a directory replaces its tags`() {
        given(file("g/gallery", "001.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("old", "kept"), fileCount = 1, deleted = false)
        dao.applyDirectoryTags(1, "g/gallery", listOf("kept", "new"), fileCount = 1, deleted = false)

        assertTrue(dao.getFilesWithTag("old").isEmpty())
        assertEquals(1, dao.getFilesWithTag("kept").size)
        assertEquals(1, dao.getFilesWithTag("new").size)
    }

    @Test
    fun `a tombstone drops the directory and its tags`() {
        given(file("g/gallery", "001.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("t"), fileCount = 1, deleted = false)
        dao.applyDirectoryTags(1, "g/gallery", emptyList(), fileCount = 0, deleted = true)

        assertTrue(dao.getFilesWithTag("t").isEmpty())
        assertEquals(0, dao.getTags().single { it.tag == "t" }.count)
    }

    /**
     * A full pull replaces what a connection ingested, because the log it is reading is a
     * different one — a path that has since lost its tags has to lose them here too.
     * Sidecar-derived rows are not the index's to remove.
     */
    @Test
    fun `clearing ingested tags leaves sidecar tags alone`() {
        given(file("g/gallery", "001.jpg"), file("photos", "beach.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("from-index"), fileCount = 1, deleted = false)
        dao.applyFileTags(1, "photos", "beach.jpg", listOf("from-sidecar"), deleted = false)

        dao.clearIngestedTags(1)

        assertTrue(dao.getFilesWithTag("from-index").isEmpty())
        assertEquals(listOf("beach.jpg"), dao.getFilesWithTag("from-sidecar").map { it.filename })
    }

    @Test
    fun `clearing one connection leaves another alone`() {
        dao.applyDirectoryTags(1, "g/one", listOf("t"), fileCount = 5, deleted = false)
        dao.applyDirectoryTags(2, "g/two", listOf("t"), fileCount = 7, deleted = false)

        dao.clearIngestedTags(1)

        assertEquals(7, dao.getTags().single { it.tag == "t" }.count)
    }

    @Test
    fun `the ingest offset round trips and is per connection`() {
        assertNull(dao.getTagIndexIngest(1))

        dao.upsertTagIndexIngest(TagIndexIngest(1, "gen-2", 773_000L))
        dao.upsertTagIndexIngest(TagIndexIngest(2, "other", 10L))
        dao.upsertTagIndexIngest(TagIndexIngest(1, "gen-2", 900_000L))

        assertEquals(900_000L, dao.getTagIndexIngest(1)?.bytesRead)
        assertEquals("gen-2", dao.getTagIndexIngest(1)?.generation)
        assertEquals(10L, dao.getTagIndexIngest(2)?.bytesRead)
    }

    /** A pass lands as one transaction: the rows and the offset that describes them. */
    @Test
    fun `applying a pass stores the rows and the offset together`() {
        given(file("g/gallery", "001.jpg"))

        dao.applyTagIndex(
            connectionId = 1,
            replace = true,
            directories = listOf(DirectoryTagUpdate("g/gallery", listOf("t"), 1, deleted = false)),
            files = listOf(FileTagUpdate("photos", "beach.jpg", listOf("s"), deleted = false)),
            generation = "gen-2",
            bytesRead = 42L,
        )

        assertEquals(1, dao.getFilesWithTag("t").size)
        assertEquals(42L, dao.getTagIndexIngest(1)?.bytesRead)
    }
}
