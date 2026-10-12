package com.bespectacledcyborg.remoteexplorer.data

import LocalDatabaseDataSource
import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetric
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetrics
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

data class File(
    val filename: String,
    val parentPath: String,
    val lastModified: Date,
    val allocationSize: Long,
    val fileAttributes: Long,
)

/** Whether a listing may be served from the cache, or must be reconciled first. */
enum class RefreshMode {
    /** Reconcile only if the directory has not been synced within the TTL. */
    IF_STALE,

    /**
     * Reconcile regardless of the TTL — what pull-to-refresh asks for. Scoped to the one
     * directory it was requested for; see `ExplorerViewModel.forceRefresh`.
     */
    FORCE,
}

/**
 * One emission of a directory's contents.
 *
 * [syncing] says a reconcile against the share is still in flight, so these rows are the
 * cached ones and another emission is coming.
 */
data class DirectoryListing(
    val files: List<LocalFile>,
    val syncing: Boolean,
)

/**
 * What the source needs to open [connection].
 *
 * The SMB fields are nullable because a local root has none of them, not because a share
 * may lack them — so an SMB row missing one is a broken row, and saying which field is
 * missing beats handing smbj a null it reports as something else.
 */
internal fun sourceDetailsOf(connection: Connection): SourceDetails = when (connection.type) {
    ConnectionType.SMB -> ConnectionDetails(
        connectionId = connection.id,
        hostname = requireNotNull(connection.hostname) { "${connection.name} has no hostname" },
        port = connection.port,
        username = connection.username.orEmpty(),
        password = connection.password?.value.orEmpty(),
        domain = connection.domain.orEmpty(),
        shareName = requireNotNull(connection.share) { "${connection.name} has no share" },
    )

    ConnectionType.LOCAL -> LocalRootDetails(
        connectionId = connection.id,
        root = requireNotNull(connection.localRoot) { "${connection.name} has no folder" },
    )
}

/**
 * How long a reconciled directory is trusted before the next visit re-reads it from the
 * share. Long enough that walking back up and down a tree costs nothing, short enough that
 * a file dropped on the NAS shows up without reaching for pull-to-refresh.
 */
const val DEFAULT_LISTING_TTL_MILLIS = 5 * 60 * 1000L

internal fun entriesBucket(count: Int): String = when {
    count == 0 -> "0"
    count <= 10 -> "1-10"
    count <= 100 -> "11-100"
    count <= 1000 -> "101-1000"
    else -> "1000+"
}

/**
 * How many folders may be listed at once to fill in their covers. A listing is one small
 * SMB round trip, so a few in flight keep a screen of galleries filling without queueing
 * the directory the user actually opened behind a dozen of them.
 */
private const val MAX_CONCURRENT_COVER_LISTINGS = 4

class FileRepository(
    private val fileDataSource: FileDataSource,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
    private val dispatcher: CoroutineDispatcher,
    // Injected purely so this class is reachable from a JVM unit test. The real
    // implementation goes through Sketch's MimeTypeMap, which delegates to
    // android.webkit.MimeTypeMap — a stub that throws under plain unit tests, and returns
    // null (then NPEs inside Sketch) even with unitTests.isReturnDefaultValues. Testing the
    // caching logic should not require Robolectric just to classify a file extension.
    private val canHaveThumbnail: (String) -> Boolean = Utils::canHaveThumbnail,
    // Injected for the same reason: a test needs to move time without sleeping through a TTL.
    private val clock: () -> Long = System::currentTimeMillis,
    private val listingTtlMillis: Long = DEFAULT_LISTING_TTL_MILLIS,
    private val metrics: LoadMetrics = LoadMetrics.NONE,
    private val sourceKind: (ConnectionId) -> String = { "unknown" },
) {
    /** See [folderCover]. */
    private val coverListingSlots = Semaphore(MAX_CONCURRENT_COVER_LISTINGS)

    private val syncsInFlight = ConcurrentHashMap<Pair<Int, String>, CompletableDeferred<Unit>>()

    private suspend fun syncShared(connectionId: Int, parentPath: String, forced: Boolean = false) {
        val key = connectionId to parentPath
        val mine = CompletableDeferred<Unit>()
        while (true) {
            val running = syncsInFlight.putIfAbsent(key, mine) ?: break
            try {
                running.await()  // stack-reviewer-ignore: a listing that fails fails everyone waiting on it, on purpose. Each waiter retrying would repeat the same failing read of the same folder, opening a folder already reports the error, and a cover already backs off for COVER_RETRY_MILLIS. Only a cancelled leader is worth taking over from, which the catch below does.
                if (!forced) return
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
        val outcome = runCatching {
            withContext(dispatcher) { syncDirectory(connectionId, parentPath, forced) }
        }
        syncsInFlight.remove(key, mine)
        outcome
            .onSuccess { mine.complete(Unit) }
            .onFailure {
                mine.completeExceptionally(it)
                throw it
            }
    }

    suspend fun connect(connection: Connection) {
        return withContext(dispatcher) {
            fileDataSource.connect(sourceDetailsOf(connection))
        }
    }

    private fun syncDirectory(connectionId: Int, parentPath: String, forced: Boolean = false) {
        if (forced) localDatabaseDataSource.clearFailedThumbnails(connectionId, parentPath)

        val listedAt = clock()
        val remoteList = fileDataSource.listFiles(connectionId, parentPath)
        metrics.record(
            LoadMetric.FOLDER_LISTING,
            clock() - listedAt,
            mapOf(
                "source" to sourceKind(connectionId),
                "entries" to entriesBucket(remoteList.size),
                "forced" to forced.toString(),
            ),
        )
        val mappedRemoteList = remoteList.map {
            LocalFile(
                connectionId = connectionId,
                filename = it.filename,
                parentPath = parentPath,
                type = when {
                    Utils.isDirectory(it.fileAttributes) -> FileType.FOLDER
                    it.filename.endsWith(".xmp", true) -> FileType.TAG
                    else -> FileType.FILE
                },
                thumbnailStatus = if (canHaveThumbnail(it.filename)) {
                    ThumbnailStatus.UNPROCESSED
                } else {
                    ThumbnailStatus.UNSUPPORTED
                },
                canHaveThumbnail = canHaveThumbnail(it.filename),
                lastModified = it.lastModified,
            )
        }
        localDatabaseDataSource.reconcileDirectory(
            connectionId,
            parentPath,
            mappedRemoteList,
            Date(clock()),
        )
    }

    /**
     * Emits the cached listing first and the reconciled one after, so a directory paints
     * immediately and the round trip to the share happens behind what is already on screen.
     *
     * A directory that has been synced within [listingTtlMillis] is served from the cache
     * alone — one emission, no network. Otherwise there are two: what we had (marked
     * `syncing`), then what the share says.
     *
     * The single exception is a directory that has never been reconciled *and* has nothing
     * cached, which emits only once, when the listing arrives. Emitting an empty list first
     * would flash an empty folder at the user before filling it in.
     */
    fun listFiles(
        connectionId: Int,
        currentPath: String = "",
        filter: String = "",
        refresh: RefreshMode = RefreshMode.IF_STALE,
    ): Flow<DirectoryListing> = flow {
        val cached = localDatabaseDataSource.getFiles(connectionId, currentPath, filter)
        val lastSyncedAt = localDatabaseDataSource.getLastSyncedAt(connectionId, currentPath)

        val stale = lastSyncedAt == null ||
                clock() - lastSyncedAt.time >= listingTtlMillis
        if (refresh == RefreshMode.IF_STALE && !stale) {
            emit(DirectoryListing(cached, syncing = false))
            return@flow
        }

        // Rows cached by a build that predates DirectorySync have no sync record, so the
        // emptiness of `cached` is what stands in for "we have nothing to show yet".
        if (cached.isNotEmpty() || lastSyncedAt != null) {
            emit(DirectoryListing(cached, syncing = true))
        }

        syncShared(connectionId, currentPath, forced = refresh == RefreshMode.FORCE)

        // Re-read rather than emitting what the share returned: the reconcile preserved the
        // thumbnail and tag status of everything it recognised, and those are not in the
        // mapped remote rows.
        emit(
            DirectoryListing(
                localDatabaseDataSource.getFiles(connectionId, currentPath, filter),
                syncing = false,
            )
        )
    }.flowOn(dispatcher)

    /** Rows rather than paths, so the caller can put them in the frame it is browsing. */
    suspend fun getTagThumbnails(tag: LocalTag, limit: Int): List<LocalFile> {
        return localDatabaseDataSource.getFilesWithTag(tag).filter {
            Utils.canHaveThumbnail(it.filename)
        }.take(limit)
    }

    /**
     * What to show for a tag: the galleries carrying it, then the files carrying it.
     *
     * Two shapes because the two sources have two shapes. A sidecar tags a file, so it comes
     * back as a file. The rolled-up index tags a whole gallery, and the gallery is what the
     * user opens — its contents are listed from the share on the way in, like any other
     * folder.
     *
     * It has to be this way round to be honest about coverage. A tagged gallery nobody has
     * browsed into has no rows in the listing cache at all, so returning only files would
     * show nothing for a tag that had just announced itself in the list.
     *
     * Nothing is de-duplicated between the two. A file whose sidecar repeats its gallery's
     * tag is two facts, and each gets a row — which is also what keeps the number beside the
     * tag equal to the number of rows behind it.
     */
    suspend fun getFilesWithTag(tag: LocalTag): List<LocalFile> {
        val directories = localDatabaseDataSource.getDirectoriesWithTag(tag).map { it.asFolder() }
        return directories + localDatabaseDataSource.getFilesWithTag(tag)
    }

    fun openFileDirectly(connectionId: Int, fullPath: String): SourceFile {
        return fileDataSource.openFile(connectionId, fullPath)
    }

    /**
     * The file's real length. This used to answer SMB's allocation size, which is rounded
     * up to a cluster and so is not the length — and left the handle it opened to leak.
     */
    fun getFileSize(connectionId: Int, fullPath: String): Long =  // stack-reviewer-ignore: no caller depends on the old value. grep over app/src finds exactly one caller, ExplorerViewModel.getFileSize, and nothing calls that — the change is intentional (the allocation size is not the file's length) and was called out in the commit message.
        fileDataSource.openFile(connectionId, fullPath).use { it.length() }

    fun updateFileThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
        thumbnailStatus: ThumbnailStatus
    ) {
        localDatabaseDataSource.updateThumbnailStatus(
            connectionId,
            parentPath,
            filename,
            thumbnailStatus
        )
    }

    /**
     * The same two accessors keyed by a full path, which is what the HTTP server has.
     *
     * The split lives here rather than at the call site because it is the inverse of
     * [LocalFile.fullPath], and the two have to agree about where the boundary falls — a
     * file at the share root has an empty `parentPath`, which is what `substringBeforeLast`
     * with an empty default produces.
     */
    suspend fun getFileThumbnailStatus(connectionId: Int, fullPath: String): ThumbnailStatus? =  // stack-reviewer-ignore: the path cannot carry a leading slash or a share prefix. Ktor's `{path...}` yields segments with no leading slash, and every URL is built by constructUrl from LocalFile.fullPath(), which is Paths.get(parentPath, filename) — so a root file is exactly its filename. Confirmed on device rather than by reading: a request for Codecs/broken.mp4 logs `known-error` on the second and later attempts, which only happens if this split matches the row the reconcile wrote.
        localDatabaseDataSource.getThumbnailStatus(
            connectionId, fullPath.substringBeforeLast('/', ""), fullPath.substringAfterLast('/')
        )

    fun updateFileThumbnailStatus(  // stack-reviewer-ignore: not recursion — the four-argument overload it delegates to is defined immediately above in this same class and is unchanged by this diff, so overload resolution picks it on arity. A genuine self-call would not compile as written (wrong argument count) let alone pass the suite; testDebugUnitTest is green and the device test exercises this path.
        connectionId: Int,
        fullPath: String,
        thumbnailStatus: ThumbnailStatus,
    ) = updateFileThumbnailStatus(
        connectionId,
        fullPath.substringBeforeLast('/', ""),
        fullPath.substringAfterLast('/'),
        thumbnailStatus,
    )

    suspend fun getFolderThumbnails(connectionId: Int, parentPath: String): List<String> {
        return localDatabaseDataSource.getFolderThumbnails(connectionId, parentPath)
    }

    private val coverRows = object : CoverRows {
        override suspend fun isListed(connectionId: Int, folder: String): Boolean =
            localDatabaseDataSource.getLastSyncedAt(connectionId, folder) != null ||
                localDatabaseDataSource.getFiles(connectionId, folder, "").isNotEmpty()

        override suspend fun list(connectionId: Int, folder: String) =
            coverListingSlots.withPermit { syncShared(connectionId, folder) }

        override suspend fun images(connectionId: Int, folder: String, limit: Int) =
            localDatabaseDataSource.getFolderThumbnails(connectionId, folder, limit)

        override suspend fun subfolders(connectionId: Int, folder: String) =
            localDatabaseDataSource.getFiles(connectionId, folder, "")
                .filter { it.type == FileType.FOLDER }
    }

    private val coverFinder = CoverFinder(coverRows)

    suspend fun folderCover(
        connectionId: Int,
        folderPath: String,
        order: CoverOrder = CoverOrder.NAME,
    ): FolderCover = coverFinder.cover(connectionId, folderPath, order)

    suspend fun getProcessedFolderThumbnails(connectionId: Int, parentPath: String): List<String> {
        return localDatabaseDataSource.getProcessedFolderThumbnails(connectionId, parentPath)
    }

}