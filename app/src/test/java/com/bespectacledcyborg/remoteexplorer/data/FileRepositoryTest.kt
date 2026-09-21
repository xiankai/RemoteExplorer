package com.bespectacledcyborg.remoteexplorer.data

import LocalDatabaseDataSource
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalDatabaseDao
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.TagStatus
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Covers the cache-vs-server decision in [FileRepository.listFiles] and the filtering around
 * it — the logic that decides whether a listing costs a network round trip, and the search
 * path that was silently broken until it was exercised against a real share.
 *
 * Runs on the JVM with no Android, no Room and no server: [FakeFileDataSource] stands in for
 * SMB and [FakeLocalDatabaseDao] for the database, which is what the FileDataSource seam
 * exists for.
 */
class FileRepositoryTest {

    private fun remoteFile(name: String) = File(
        filename = name,
        parentPath = "Photos",
        lastModified = Date(0),
        allocationSize = 0,
        fileAttributes = 0,
    )

    private fun cachedFile(name: String) = LocalFile(
        connectionId = 1,
        parentPath = "Photos",
        filename = name,
        type = FileType.FILE,
        canHaveThumbnail = false,
        tagStatus = TagStatus.UNPROCESSED,
        thumbnailStatus = ThumbnailStatus.UNPROCESSED,
        lastModified = Date(0),
    )

    private fun repository(
        remote: FakeFileDataSource,
        dao: FakeLocalDatabaseDao,
    ): FileRepository {
        val dispatcher = UnconfinedTestDispatcher()
        return FileRepository(
            remote,
            LocalDatabaseDataSource(dao, dispatcher),
            dispatcher,
            // The real predicate needs android.webkit.MimeTypeMap. Nothing here asserts on
            // thumbnail eligibility, so a constant keeps Android out of these tests.
            canHaveThumbnail = { false },
        )
    }

    @Test
    fun `serves a cached listing without touching the server`() = runTest {
        val remote = FakeFileDataSource()
        val dao = FakeLocalDatabaseDao(cached = listOf(cachedFile("beach.jpg")))

        val files = repository(remote, dao).listFiles(1, "Photos")

        assertEquals(listOf("beach.jpg"), files.map { it.filename })
        assertEquals("cache hit must not reach the server", 0, remote.listCalls)
    }

    @Test
    fun `falls back to the server when nothing is cached`() = runTest {
        val remote = FakeFileDataSource(listOf(remoteFile("city.jpg")))
        val dao = FakeLocalDatabaseDao(cached = emptyList())

        val files = repository(remote, dao).listFiles(1, "Photos")

        assertEquals(listOf("city.jpg"), files.map { it.filename })
        assertEquals(1, remote.listCalls)
        assertTrue("a server listing must be written back to the cache", dao.upserted.isNotEmpty())
    }

    @Test
    fun `forceRemote ignores a populated cache`() = runTest {
        val remote = FakeFileDataSource(listOf(remoteFile("fresh.jpg")))
        val dao = FakeLocalDatabaseDao(cached = listOf(cachedFile("stale.jpg")))

        val files = repository(remote, dao).listFiles(1, "Photos", forceRemote = true)

        assertEquals(listOf("fresh.jpg"), files.map { it.filename })
        assertEquals(1, remote.listCalls)
    }

    @Test
    fun `filters a server listing case-insensitively`() = runTest {
        // Regression: this used to be a case-sensitive `contains`, so searching "beach" did
        // not match "Beach.jpg" while the database path (SQLite LIKE) matched it happily.
        val remote = FakeFileDataSource(
            listOf(remoteFile("Beach.jpg"), remoteFile("city.jpg"))
        )
        val dao = FakeLocalDatabaseDao(cached = emptyList())

        val files = repository(remote, dao).listFiles(1, "Photos", filter = "beach")

        assertEquals(listOf("Beach.jpg"), files.map { it.filename })
    }

    @Test
    fun `a filtered listing is asked of the database, not the server`() = runTest {
        // The cached-and-filtered path is where the LIKE-without-wildcards bug lived: the
        // repository hands the search term to the DAO and trusts it, so what matters here is
        // that the DAO is the one asked, with the term passed through untouched.
        val remote = FakeFileDataSource(listOf(remoteFile("should-not-be-used.jpg")))
        val dao = FakeLocalDatabaseDao(
            cached = listOf(cachedFile("Beach.jpg"), cachedFile("city.jpg"))
        )

        val files = repository(remote, dao).listFiles(1, "Photos", filter = "beach")

        assertEquals(listOf("Beach.jpg"), files.map { it.filename })
        assertEquals("a cached listing must not reach the server", 0, remote.listCalls)
        assertEquals("the search term must reach the DAO unaltered", listOf("beach"), dao.filters)
    }

    @Test
    fun `an unmatched filter returns nothing rather than everything`() = runTest {
        val remote = FakeFileDataSource(listOf(remoteFile("city.jpg")))
        val dao = FakeLocalDatabaseDao(cached = emptyList())

        val files = repository(remote, dao).listFiles(1, "Photos", filter = "nosuchthing")

        assertEquals(emptyList<String>(), files.map { it.filename })
    }
}

/** Stands in for SMB. [openFile] is unfakeable and unused by anything under test. */
private class FakeFileDataSource(
    private val files: List<File> = emptyList(),
) : FileDataSource {
    var listCalls = 0
        private set

    override fun connect(details: ConnectionDetails) = Unit

    override fun listFiles(
        connectionId: ConnectionId,
        currentPath: String,
        filter: String,
    ): List<File> {
        listCalls++
        return files
    }

    override fun openFile(connectionId: ConnectionId, path: String) =
        throw UnsupportedOperationException("no SMBJ handle in a unit test")
}

/**
 * Stands in for Room. Only the methods [FileRepository] reaches are implemented; the rest
 * throw, so a test that starts depending on them fails loudly rather than on a silent empty
 * list.
 */
private class FakeLocalDatabaseDao(
    private val cached: List<LocalFile>,
) : LocalDatabaseDao {
    val upserted = mutableListOf<LocalFile>()

    /** Every filter the repository passed down, so a test can assert it arrived intact. */
    val filters = mutableListOf<String>()

    override fun getFiles(connectionId: Int, parentPath: String) = cached

    override fun getFilesWithFilter(connectionId: Int, parentPath: String, filter: String):
        List<LocalFile> {
        filters += filter
        // Stands in for `filename LIKE '%' || :filter || '%'`: a substring match, and
        // case-insensitive because SQLite's LIKE is for ASCII. The real query wraps the
        // wildcards itself, which is why the repository passes the bare term.
        return cached.filter { it.filename.contains(filter, ignoreCase = true) }
    }

    override fun updateFileList(files: List<LocalFile>) {
        upserted += files
    }

    override fun getFilesWithTag(tag: String): List<LocalFile> = notUsed()
    override fun getFolders(connectionId: Int, parentPath: String, type: FileType) = notUsed()
    override fun getFolderThumbnails(connectionId: Int, parentPath: String, limit: Int) = notUsed()
    override fun getProcessedFolderThumbnails(
        connectionId: Int,
        parentPath: String,
        thumbnailStatus: ThumbnailStatus,
        limit: Int,
    ): List<String> = notUsed()

    override fun getTags(): List<LocalTagWithMetadata> = notUsed()
    override fun addTag(tag: LocalTag) = notUsed()
    override fun addFileTagRelation(relation: com.bespectacledcyborg.remoteexplorer.data.db.FileTagRelation) =
        notUsed()

    override fun updateTagStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
        status: TagStatus,
    ) = notUsed()

    override fun updateThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
        status: ThumbnailStatus,
    ) = notUsed()

    override fun getUnprocessedTagFiles(
        connectionId: Int,
        limit: Int,
        type: FileType,
        status: TagStatus,
    ): List<LocalFile> = notUsed()

    override fun getAllTagFiles(connectionId: Int, limit: Int, type: FileType): List<LocalFile> =
        notUsed()

    private fun notUsed(): Nothing =
        throw UnsupportedOperationException("not part of the behaviour under test")
}
