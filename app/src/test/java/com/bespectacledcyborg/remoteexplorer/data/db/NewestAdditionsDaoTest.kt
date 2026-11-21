package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class NewestAdditionsDaoTest {

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

    private fun gallery(
        path: String,
        addedAt: Long?,
        tags: List<String> = listOf("a"),
        deleted: Boolean = false,
    ) = DirectoryTagUpdate(path, tags, deleted, addedAt = addedAt)

    private fun apply(connectionId: Int = 1, replace: Boolean = false, vararg galleries: DirectoryTagUpdate) =
        dao.applyTagIndex(connectionId, replace, galleries.toList(), emptyList(), "1", 0L)

    private fun newest(limit: Int = 200) = dao.getNewestDirectories(limit).map { it.parentPath }

    @Test
    fun `lists galleries newest first across connections`() {
        apply(1, false, gallery("g/old", 100), gallery("g/new", 300))
        apply(2, false, gallery("g/middle", 200))

        assertEquals(listOf("g/new", "g/middle", "g/old"), newest())
    }

    @Test
    fun `stops at the limit`() {
        apply(1, false, *(1..250).map { gallery("g/$it", it.toLong()) }.toTypedArray())

        val listed = newest()
        assertEquals(200, listed.size)
        assertEquals("g/250", listed.first())
        assertEquals("g/51", listed.last())
    }

    @Test
    fun `an untagged folder is listed`() {
        apply(1, false, gallery("Download", 100, tags = emptyList()))

        assertEquals(listOf("Download"), newest())
    }

    @Test
    fun `a tombstone removes the gallery`() {
        apply(1, false, gallery("g/x", 100))
        apply(1, false, gallery("g/x", null, deleted = true))

        assertEquals(emptyList<String>(), newest())
    }

    @Test
    fun `a re-appended record keeps one row with its latest date`() {
        apply(1, false, gallery("g/x", 100), gallery("g/y", 200))
        apply(1, false, gallery("g/x", 300))

        assertEquals(listOf("g/x", "g/y"), newest())
    }

    @Test
    fun `a record without added_at is not listed`() {
        apply(1, false, gallery("g/x", null))

        assertEquals(emptyList<String>(), newest())
    }

    @Test
    fun `a full pull replaces only its own connection`() {
        apply(1, false, gallery("g/stale", 100))
        apply(2, false, gallery("g/other", 150))
        apply(1, true, gallery("g/fresh", 200))

        assertEquals(listOf("g/fresh", "g/other"), newest())
    }

    @Test
    fun `a listed gallery is a folder with no modified date of its own`() {
        apply(1, false, gallery("gallery-dl/pixiv/[1] x", 1_790_047_563))

        val folder = dao.getNewestDirectories(1).single().asFolder()
        assertEquals(FileType.FOLDER, folder.type)
        assertEquals("gallery-dl/pixiv", folder.parentPath)
        assertEquals("[1] x", folder.filename)
        assertEquals(Date(0), folder.lastModified)
    }

    @Test
    fun `a browsed folder finds its arrival by its full path`() {
        apply(1, false, gallery("gallery-dl/pixiv/[1] x", 1_790_047_563), gallery("gallery-dl/pixiv/[2] y", 5))
        val browsed = LocalFile(
            connectionId = 1, parentPath = "gallery-dl/pixiv", filename = "[1] x",
            type = FileType.FOLDER, canHaveThumbnail = false, lastModified = Date(0),
        )

        val found = dao.getIndexedDirectories(1, listOf(browsed.fullPath()))

        assertEquals(listOf(IndexedDirectory(1, "gallery-dl/pixiv/[1] x", 1_790_047_563)), found)
        assertEquals(emptyList<IndexedDirectory>(), dao.getIndexedDirectories(2, listOf(browsed.fullPath())))
    }
}
