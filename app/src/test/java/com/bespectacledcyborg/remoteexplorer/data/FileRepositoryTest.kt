package com.bespectacledcyborg.remoteexplorer.data

import LocalDatabaseDataSource
import com.bespectacledcyborg.remoteexplorer.data.db.DirTagRelation
import com.bespectacledcyborg.remoteexplorer.data.db.DirectorySync
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalDatabaseDao
import com.bespectacledcyborg.remoteexplorer.data.db.TagAlias
import com.bespectacledcyborg.remoteexplorer.data.db.TagAliasIngest
import com.bespectacledcyborg.remoteexplorer.data.db.TagAliasPair
import com.bespectacledcyborg.remoteexplorer.data.db.TagIndexIngest
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFileStamp
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.TagStatus
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Covers the cache-vs-server decision in [FileRepository.listFiles] and the reconcile behind
 * it — the logic that decides whether a listing costs a network round trip, what a listing
 * is allowed to overwrite when it comes back, and the search path that was silently broken
 * until it was exercised against a real share.
 *
 * Runs on the JVM with no Android, no Room and no server: [FakeFileDataSource] stands in for
 * SMB and [FakeLocalDatabaseDao] for the database, which is what the FileDataSource seam
 * exists for. `reconcileDirectory` is a `@Transaction` default method on the DAO interface,
 * so the fake inherits the real implementation and these tests exercise it directly rather
 * than a restatement of it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher
class FileRepositoryTest {

    private val ttl = 60_000L

    /** Movable clock, so a TTL can be crossed without sleeping through it. */
    private var now = 1_000_000L

    private fun remoteFile(name: String, lastModified: Long = 0) = File(
        filename = name,
        parentPath = "Photos",
        lastModified = Date(lastModified),
        allocationSize = 0,
        fileAttributes = 0,
    )

    private fun cachedFile(
        name: String,
        lastModified: Long = 0,
        tagStatus: TagStatus = TagStatus.UNPROCESSED,
        thumbnailStatus: ThumbnailStatus = ThumbnailStatus.UNPROCESSED,
    ) = LocalFile(
        connectionId = 1,
        parentPath = "Photos",
        filename = name,
        type = FileType.FILE,
        canHaveThumbnail = false,
        tagStatus = tagStatus,
        thumbnailStatus = thumbnailStatus,
        lastModified = Date(lastModified),
    )

    private fun repository(
        remote: FakeFileDataSource,
        dao: FakeLocalDatabaseDao,
        // The real predicate needs android.webkit.MimeTypeMap. A constant keeps Android out
        // of these tests; a test that cares which branch of the mapping it lands in says so.
        canHaveThumbnail: (String) -> Boolean = { false },
    ): FileRepository {
        val dispatcher = UnconfinedTestDispatcher()
        return FileRepository(
            remote,
            LocalDatabaseDataSource(dao, dispatcher),
            dispatcher,
            canHaveThumbnail = canHaveThumbnail,
            clock = { now },
            listingTtlMillis = ttl,
        )
    }

    /** A directory synced just now, so the TTL has not expired. */
    private fun freshlySynced(dao: FakeLocalDatabaseDao) {
        dao.upsertDirectorySync(DirectorySync(1, "Photos", Date(now)))
    }

    @Test
    fun `serves a cached listing without touching the server`() = runTest {
        val remote = FakeFileDataSource()
        val dao = FakeLocalDatabaseDao(cached = listOf(cachedFile("beach.jpg")))
        freshlySynced(dao)

        val emissions = repository(remote, dao).listFiles(1, "Photos").toList()

        assertEquals(1, emissions.size)
        assertEquals(listOf("beach.jpg"), emissions.single().files.map { it.filename })
        assertEquals("a listing inside the TTL must not reach the server", 0, remote.listCalls)
    }

    @Test
    fun `an empty directory inside the ttl is not re-listed`() = runTest {
        // Regression: the cache was consulted with `ifEmpty`, so a directory that is
        // genuinely empty looked exactly like one that had never been fetched and went back
        // to SMB on every single visit. The sync record is what tells them apart.
        val remote = FakeFileDataSource()
        val dao = FakeLocalDatabaseDao(cached = emptyList())
        freshlySynced(dao)

        val emissions = repository(remote, dao).listFiles(1, "Photos").toList()

        assertEquals(emptyList<String>(), emissions.single().files.map { it.filename })
        assertEquals(0, remote.listCalls)
    }

    @Test
    fun `emits the cached listing before the reconciled one`() = runTest {
        val remote = FakeFileDataSource(listOf(remoteFile("fresh.jpg")))
        val dao = FakeLocalDatabaseDao(cached = listOf(cachedFile("stale.jpg")))
        dao.upsertDirectorySync(DirectorySync(1, "Photos", Date(now - ttl)))

        val emissions = repository(remote, dao).listFiles(1, "Photos").toList()

        assertEquals(2, emissions.size)
        assertEquals(listOf("stale.jpg"), emissions[0].files.map { it.filename })
        assertTrue("the cached emission must say more is coming", emissions[0].syncing)
        assertEquals(listOf("fresh.jpg"), emissions[1].files.map { it.filename })
        assertTrue("the reconciled emission is final", !emissions[1].syncing)
    }

    @Test
    fun `a directory never synced emits only once`() = runTest {
        // Nothing cached and no sync record: emitting an empty list first would flash an
        // empty folder before filling it in.
        val remote = FakeFileDataSource(listOf(remoteFile("city.jpg")))
        val dao = FakeLocalDatabaseDao(cached = emptyList())

        val emissions = repository(remote, dao).listFiles(1, "Photos").toList()

        assertEquals(1, emissions.size)
        assertEquals(listOf("city.jpg"), emissions.single().files.map { it.filename })
        assertEquals(1, remote.listCalls)
    }

    @Test
    fun `re-lists once the ttl has passed`() = runTest {
        val remote = FakeFileDataSource(listOf(remoteFile("beach.jpg")))
        val dao = FakeLocalDatabaseDao(cached = listOf(cachedFile("beach.jpg")))
        freshlySynced(dao)
        val repository = repository(remote, dao)

        repository.listFiles(1, "Photos").toList()
        assertEquals(0, remote.listCalls)

        now += ttl
        repository.listFiles(1, "Photos").toList()
        assertEquals(1, remote.listCalls)
    }

    @Test
    fun `a forced refresh ignores the ttl`() = runTest {
        val remote = FakeFileDataSource(listOf(remoteFile("fresh.jpg")))
        val dao = FakeLocalDatabaseDao(cached = listOf(cachedFile("stale.jpg")))
        freshlySynced(dao)

        val emissions = repository(remote, dao)
            .listFiles(1, "Photos", refresh = RefreshMode.FORCE)
            .toList()

        assertEquals(1, remote.listCalls)
        assertEquals(listOf("fresh.jpg"), emissions.last().files.map { it.filename })
    }

    @Test
    fun `reconciling keeps the derived state of a file that has not changed`() = runTest {
        // The bug this replaces: the listing was written back with a single @Upsert, which
        // writes every column — so a refresh reset thumbnailStatus and tagStatus for the
        // whole directory and the app re-derived both for files that had not moved.
        val remote = FakeFileDataSource(listOf(remoteFile("beach.jpg", lastModified = 10)))
        val dao = FakeLocalDatabaseDao(
            cached = listOf(
                cachedFile(
                    "beach.jpg",
                    lastModified = 10,
                    tagStatus = TagStatus.SUCCESS,
                    thumbnailStatus = ThumbnailStatus.SUCCESS,
                )
            )
        )

        repository(remote, dao).listFiles(1, "Photos").toList()

        val reconciled = dao.getFiles(1, "Photos").single()
        assertEquals(TagStatus.SUCCESS, reconciled.tagStatus)
        assertEquals(ThumbnailStatus.SUCCESS, reconciled.thumbnailStatus)
    }

    @Test
    fun `a failed thumbnail survives an ordinary reconcile`() = runTest {
        // The point of recording ERROR at all: an undecodable file must not be re-read and
        // re-decoded every time its cell scrolls back into view. An unforced listing is
        // exactly that case, so the record has to outlive it.
        val remote = FakeFileDataSource(listOf(remoteFile("clip.mp4", lastModified = 10)))
        val dao = FakeLocalDatabaseDao(
            cached = listOf(
                cachedFile("clip.mp4", lastModified = 10, thumbnailStatus = ThumbnailStatus.ERROR)
            )
        )
        now += ttl + 1

        repository(remote, dao).listFiles(1, "Photos").toList()

        assertEquals(ThumbnailStatus.ERROR, dao.getFiles(1, "Photos").single().thumbnailStatus)
    }

    @Test
    fun `pull-to-refresh puts a failed thumbnail back in the queue`() = runTest {
        // And the escape hatch. A recorded failure otherwise outlives the app gaining a
        // decoder that could now read the file, which has already happened once, so the
        // user needs some way to ask again that is not reinstalling.
        val remote = FakeFileDataSource(listOf(remoteFile("clip.mp4", lastModified = 10)))
        val dao = FakeLocalDatabaseDao(
            cached = listOf(
                cachedFile("clip.mp4", lastModified = 10, thumbnailStatus = ThumbnailStatus.ERROR)
            )
        )

        repository(remote, dao).listFiles(1, "Photos", refresh = RefreshMode.FORCE).toList()

        assertEquals(
            ThumbnailStatus.UNPROCESSED,
            dao.getFiles(1, "Photos").single().thumbnailStatus,
        )
    }

    @Test
    fun `pull-to-refresh leaves a successful thumbnail alone`() = runTest {
        // Only failures are retried. Clearing SUCCESS too would make every refresh throw
        // away the whole directory's thumbnails and regenerate them, which is the cost this
        // work exists to remove.
        val remote = FakeFileDataSource(listOf(remoteFile("beach.jpg", lastModified = 10)))
        val dao = FakeLocalDatabaseDao(
            cached = listOf(
                cachedFile("beach.jpg", lastModified = 10, thumbnailStatus = ThumbnailStatus.SUCCESS)
            )
        )

        repository(remote, dao).listFiles(1, "Photos", refresh = RefreshMode.FORCE).toList()

        assertEquals(
            ThumbnailStatus.SUCCESS,
            dao.getFiles(1, "Photos").single().thumbnailStatus,
        )
    }

    @Test
    fun `reconciling re-derives a file whose contents changed`() = runTest {
        val remote = FakeFileDataSource(listOf(remoteFile("beach.jpg", lastModified = 20)))
        val dao = FakeLocalDatabaseDao(
            cached = listOf(
                cachedFile(
                    "beach.jpg",
                    lastModified = 10,
                    tagStatus = TagStatus.SUCCESS,
                    thumbnailStatus = ThumbnailStatus.SUCCESS,
                )
            )
        )

        // Thumbnail-eligible, so "re-derive" means back to UNPROCESSED rather than to the
        // UNSUPPORTED this mapping gives anything that cannot have one.
        repository(remote, dao, canHaveThumbnail = { true }).listFiles(1, "Photos").toList()

        val reconciled = dao.getFiles(1, "Photos").single()
        assertEquals(Date(20), reconciled.lastModified)
        assertEquals(TagStatus.UNPROCESSED, reconciled.tagStatus)
        assertEquals(ThumbnailStatus.UNPROCESSED, reconciled.thumbnailStatus)
    }

    @Test
    fun `reconciling forgets a file the server no longer lists`() = runTest {
        // @Upsert could only ever add or update, so a file deleted on the share stayed in
        // the cache for the life of the install and the gallery kept offering it.
        val remote = FakeFileDataSource(listOf(remoteFile("kept.jpg")))
        val dao = FakeLocalDatabaseDao(
            cached = listOf(cachedFile("kept.jpg"), cachedFile("deleted-on-server.jpg"))
        )

        val emissions = repository(remote, dao).listFiles(1, "Photos").toList()

        assertEquals(listOf("kept.jpg"), emissions.last().files.map { it.filename })
        assertEquals(listOf("kept.jpg"), dao.getFiles(1, "Photos").map { it.filename })
        assertEquals(
            "its tag relations must go with it, or they keep inflating tag counts",
            listOf("deleted-on-server.jpg"),
            dao.deletedTagRelations,
        )
    }

    @Test
    fun `an empty listing empties the directory`() = runTest {
        // Deliberate, and the reason is in the pragma on the delete pass: getting here means
        // SMB answered, and smbj throws rather than returning empty when a directory is
        // missing or the share is unreachable. Guarding this would leave a directory that was
        // genuinely emptied on the share showing its old contents forever.
        val remote = FakeFileDataSource(emptyList())
        val dao = FakeLocalDatabaseDao(cached = listOf(cachedFile("gone.jpg")))

        val emissions = repository(remote, dao).listFiles(1, "Photos").toList()

        assertEquals(emptyList<String>(), emissions.last().files.map { it.filename })
        assertEquals(emptyList<String>(), dao.getFiles(1, "Photos").map { it.filename })
    }

    @Test
    fun `records the sync so the next visit can be served from the cache`() = runTest {
        val remote = FakeFileDataSource(listOf(remoteFile("city.jpg")))
        val dao = FakeLocalDatabaseDao(cached = emptyList())

        assertNull(dao.getDirectorySync(1, "Photos"))
        repository(remote, dao).listFiles(1, "Photos").toList()

        assertEquals(Date(now), dao.getDirectorySync(1, "Photos")?.lastSyncedAt)
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
        freshlySynced(dao)

        val emissions = repository(remote, dao).listFiles(1, "Photos", filter = "beach").toList()

        assertEquals(listOf("Beach.jpg"), emissions.single().files.map { it.filename })
        assertEquals("a cached listing must not reach the server", 0, remote.listCalls)
        assertEquals("the search term must reach the DAO unaltered", listOf("beach"), dao.filters)
    }

    @Test
    fun `a search matching nothing cached does not fall through to the server`() = runTest {
        // Another face of the `ifEmpty` bug: an empty result looked like an empty cache, so
        // every keystroke of a term that matched nothing cost a full SMB listing.
        val remote = FakeFileDataSource(listOf(remoteFile("city.jpg")))
        val dao = FakeLocalDatabaseDao(cached = listOf(cachedFile("city.jpg")))
        freshlySynced(dao)

        val emissions = repository(remote, dao)
            .listFiles(1, "Photos", filter = "nosuchthing")
            .toList()

        assertEquals(emptyList<String>(), emissions.single().files.map { it.filename })
        assertEquals(0, remote.listCalls)
    }

    @Test
    fun `filters a reconciled listing case-insensitively`() = runTest {
        // Regression: this used to be a case-sensitive `contains` applied in memory, so
        // searching "beach" did not match "Beach.jpg" while the database path (SQLite LIKE)
        // matched it happily. The reconciled listing is now re-read through the same query.
        val remote = FakeFileDataSource(
            listOf(remoteFile("Beach.jpg"), remoteFile("city.jpg"))
        )
        val dao = FakeLocalDatabaseDao(cached = emptyList())

        val emissions = repository(remote, dao).listFiles(1, "Photos", filter = "beach").toList()

        assertEquals(listOf("Beach.jpg"), emissions.last().files.map { it.filename })
    }

    @Test
    fun `a folder nobody has opened is listed once to make its cover`() = runTest {
        // #66: a cover is made from the folder's own rows, so a gallery never opened had
        // none and its tile stayed a blank folder for good.
        val remote = FakeFileDataSource(
            listOf("c.jpg", "notes.txt", "a.jpg", "b.jpg", "e.jpg", "d.jpg").map { remoteFile(it) }
        )
        val dao = FakeLocalDatabaseDao(cached = emptyList())
        val repository = repository(remote, dao, canHaveThumbnail = { it.endsWith(".jpg") })

        val cover = repository.folderCover(1, "Photos")

        assertEquals(1, remote.listCalls)
        assertEquals(
            "only images, in name order, four of them",
            listOf("Photos/a.jpg", "Photos/b.jpg", "Photos/c.jpg", "Photos/d.jpg"),
            cover,
        )

        repository.folderCover(1, "Photos")
        assertEquals("the second ask is answered from the cache", 1, remote.listCalls)
    }

    @Test
    fun `a folder already looked at is not re-listed for its cover`() = runTest {
        // Even with nothing to show and the TTL long gone: staleness is for opening the
        // folder to deal with, not for scrolling past it.
        val remote = FakeFileDataSource(listOf(remoteFile("new.jpg")))
        val dao = FakeLocalDatabaseDao(cached = emptyList())
        dao.upsertDirectorySync(DirectorySync(1, "Photos", Date(now - 10 * ttl)))

        val cover = repository(remote, dao, canHaveThumbnail = { true }).folderCover(1, "Photos")

        assertEquals(0, remote.listCalls)
        assertEquals(emptyList<String>(), cover)
    }

    @Test
    fun `a cover leaves out files that cannot have a thumbnail or failed to make one`() = runTest {
        val dao = FakeLocalDatabaseDao(
            cached = listOf(
                cachedFile("a.jpg", thumbnailStatus = ThumbnailStatus.ERROR),
                cachedFile("b.json", thumbnailStatus = ThumbnailStatus.UNSUPPORTED),
                cachedFile("c.jpg", thumbnailStatus = ThumbnailStatus.SUCCESS),
                cachedFile("d.jpg"),
            )
        )
        freshlySynced(dao)

        val cover = repository(FakeFileDataSource(), dao).folderCover(1, "Photos")

        assertEquals(listOf("Photos/c.jpg", "Photos/d.jpg"), cover)
    }
}

/** Stands in for SMB. Nothing under test opens a file, so [openFile] refuses. */
private class FakeFileDataSource(
    private val files: List<File> = emptyList(),
) : FileDataSource {
    var listCalls = 0
        private set

    override fun connect(details: SourceDetails) = Unit

    override fun listFiles(
        connectionId: ConnectionId,
        currentPath: String,
        filter: String,
    ): List<File> {
        listCalls++
        return files
    }

    override fun openFile(connectionId: ConnectionId, path: String) =
        throw UnsupportedOperationException("nothing in FileRepository's listing opens a file")
}

/**
 * Stands in for Room, holding the directory's rows in a list so that `reconcileDirectory` —
 * inherited from the DAO interface — operates on real state. Only the methods
 * [FileRepository] reaches are implemented; the rest throw, so a test that starts depending
 * on them fails loudly rather than on a silent empty list.
 */
private class FakeLocalDatabaseDao(
    cached: List<LocalFile>,
) : LocalDatabaseDao {
    private val files = cached.toMutableList()
    private val syncs = mutableMapOf<Pair<Int, String>, DirectorySync>()

    /** Every filter the repository passed down, so a test can assert it arrived intact. */
    val filters = mutableListOf<String>()

    /** Filenames whose tag relations were cleared by a reconcile. */
    val deletedTagRelations = mutableListOf<String>()

    override fun getFiles(connectionId: Int, parentPath: String): List<LocalFile> =
        files.filter { it.connectionId == connectionId && it.parentPath == parentPath }

    override fun getFilesWithFilter(connectionId: Int, parentPath: String, filter: String):
        List<LocalFile> {
        filters += filter
        // Stands in for `filename LIKE '%' || :filter || '%'`: a substring match, and
        // case-insensitive because SQLite's LIKE is for ASCII. The real query wraps the
        // wildcards itself, which is why the repository passes the bare term.
        return getFiles(connectionId, parentPath)
            .filter { it.filename.contains(filter, ignoreCase = true) }
    }

    override fun getFileStamps(connectionId: Int, parentPath: String): List<LocalFileStamp> =
        getFiles(connectionId, parentPath).map { LocalFileStamp(it.filename, it.lastModified) }

    override fun insertNewFiles(files: List<LocalFile>) {
        // IGNORE on conflict: a row that is already there wins.
        files.forEach { candidate ->
            val exists = this.files.any {
                it.connectionId == candidate.connectionId &&
                    it.parentPath == candidate.parentPath &&
                    it.filename == candidate.filename
            }
            if (!exists) this.files += candidate
        }
    }

    override fun refreshChangedFile(
        connectionId: Int,
        parentPath: String,
        filename: String,
        type: FileType,
        lastModified: Date,
        thumbnailStatus: ThumbnailStatus,
        tagStatus: TagStatus,
    ) {
        files.replaceAll {
            if (it.connectionId == connectionId &&
                it.parentPath == parentPath &&
                it.filename == filename
            ) {
                it.copy(
                    type = type,
                    lastModified = lastModified,
                    thumbnailStatus = thumbnailStatus,
                    tagStatus = tagStatus,
                )
            } else {
                it
            }
        }
    }

    override fun deleteFiles(connectionId: Int, parentPath: String, filenames: List<String>) {
        files.removeAll {
            it.connectionId == connectionId &&
                it.parentPath == parentPath &&
                it.filename in filenames
        }
    }

    override fun deleteFileTagRelations(
        connectionId: Int,
        parentPath: String,
        filenames: List<String>,
    ) {
        deletedTagRelations += filenames
    }

    override fun getDirectorySync(connectionId: Int, parentPath: String): DirectorySync? =
        syncs[connectionId to parentPath]

    override fun upsertDirectorySync(sync: DirectorySync) {
        syncs[sync.connectionId to sync.parentPath] = sync
    }

    override fun getFilesWithTag(tag: String): List<LocalFile> = notUsed()
    override fun getFolders(connectionId: Int, parentPath: String, type: FileType) = notUsed()
    // The query, restated: files only, none that cannot or did not make a thumbnail, by name.
    override fun getFolderThumbnails(
        connectionId: Int,
        parentPath: String,
        limit: Int,
        type: FileType,
        excluded: List<ThumbnailStatus>,
    ): List<LocalFile> = files
        .filter {
            it.connectionId == connectionId && it.parentPath == parentPath &&
                it.type == type && it.thumbnailStatus !in excluded
        }
        .sortedBy { it.filename }
        .take(limit)
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

    override fun getThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
    ): ThumbnailStatus? = files.firstOrNull { it.filename == filename }?.thumbnailStatus

    override fun clearFailedThumbnails(connectionId: Int, parentPath: String) {
        files.replaceAll {
            if (it.thumbnailStatus == ThumbnailStatus.ERROR) {
                it.copy(thumbnailStatus = ThumbnailStatus.UNPROCESSED)
            } else {
                it
            }
        }
    }

    override fun getUnprocessedTagFiles(
        connectionId: Int,
        limit: Int,
        type: FileType,
        status: TagStatus,
    ): List<LocalFile> = notUsed()

    override fun getAllTagFiles(connectionId: Int, limit: Int, type: FileType): List<LocalFile> =
        notUsed()

    // The rolled-up tag index. None of it is reachable from FileRepository, and the real
    // queries are covered against a real database in TagIndexDaoTest — a fake reimplementing
    // a UNION would only ever agree with whatever this file thought it said.
    override fun getDirectoriesWithTag(tag: String): List<DirTagRelation> = notUsed()

    override fun getTagIndexIngest(connectionId: Int): TagIndexIngest? = notUsed()

    override fun upsertTagIndexIngest(ingest: TagIndexIngest) = notUsed()

    override fun addDirTagRelation(relation: DirTagRelation) = notUsed()

    override fun deleteDirTagRelations(connectionId: Int, parentPath: String) = notUsed()

    override fun clearIngestedTags(connectionId: Int) = notUsed()

    override fun getTagAliasGeneration(connectionId: Int): String? = notUsed()

    override fun upsertTagAliasIngest(ingest: TagAliasIngest) = notUsed()

    override fun addTagAliases(aliases: List<TagAlias>) = notUsed()

    override fun clearTagAliases(connectionId: Int) = notUsed()
    override fun clearTagHidden(connectionId: Int) = notUsed()
    override fun addTagHidden(hidden: List<com.bespectacledcyborg.remoteexplorer.data.db.TagHidden>) = notUsed()
    override fun getTagAliasPairs(): List<TagAliasPair> = notUsed()
    override fun clearAnnotationKinds(connectionId: Int) = notUsed()
    override fun deleteAnnotationKinds(connectionId: Int, parentPath: String) = notUsed()
    override fun addAnnotationKinds(kinds: List<com.bespectacledcyborg.remoteexplorer.data.db.DirAnnotationKind>) = notUsed()
    override fun getAnnotatedDirectories(kind: String): List<com.bespectacledcyborg.remoteexplorer.data.db.AnnotatedDirectory> = notUsed()

    private fun notUsed(): Nothing =
        throw UnsupportedOperationException("not part of the behaviour under test")
}
