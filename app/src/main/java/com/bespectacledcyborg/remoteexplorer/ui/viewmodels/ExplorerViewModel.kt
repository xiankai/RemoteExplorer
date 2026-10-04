package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import android.app.Application
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bespectacledcyborg.remoteexplorer.data.ConnectionRepository
import com.bespectacledcyborg.remoteexplorer.data.FileRepository
import com.bespectacledcyborg.remoteexplorer.data.PairingRepository
import com.bespectacledcyborg.remoteexplorer.data.RefreshMode
import com.bespectacledcyborg.remoteexplorer.data.ServerRepository
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import com.bespectacledcyborg.remoteexplorer.data.TagRepository
import com.bespectacledcyborg.remoteexplorer.data.TagSearch
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.AllFilesAccessMissing
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.db.ViewTotals
import com.bespectacledcyborg.remoteexplorer.data.views.ViewRecorder
import com.bespectacledcyborg.remoteexplorer.data.tagPrefix
import com.bespectacledcyborg.remoteexplorer.data.tasks.ScanTagsWorker
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.bespectacledcyborg.remoteexplorer.utils.Utils.constructUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The listing's orders, each with the label a sort menu gives it. */
enum class SortType(
    val label: String,
    /** Whether the order reads what has been viewed (#76), so the totals need fetching. */
    val usesViews: Boolean = false,
) {
    NAME_ASC("Name, A to Z"),
    NAME_DESC("Name, Z to A"),
    LAST_MODIFIED_ASC("Oldest first"),
    LAST_MODIFIED_DESC("Newest first"),

    /** Popularity: most opens first, the most recently opened breaking a tie. */
    MOST_VIEWED("Most viewed", usesViews = true),

    /** Novelty: fewest opens first, and never opened (0) before anything. */
    LEAST_VIEWED("Least viewed", usesViews = true),

    /** Most recently opened first; never opened last. */
    RECENTLY_VIEWED("Recently viewed", usesViews = true),

    /** Return frequency: opened on the most distinct viewing days first. */
    MOST_DAYS_VIEWED("Viewed on most days", usesViews = true),

    /** Most time spent playing first. */
    MOST_WATCHED("Longest watched", usesViews = true),
}

/**
 * The order of anything with view totals, a file, folder or tag alike, which is what lets
 * one sort menu order all three (#76). Every order falls back to [byName], so entries that
 * tie, most of a library at 0, stay in a stable order between listings.
 */
internal fun <T> viewComparator(
    sortType: SortType,
    views: (T) -> ViewTotals,
    byName: Comparator<T>,
): Comparator<T>? = when (sortType) {
    SortType.MOST_VIEWED -> compareByDescending<T> { views(it).openCount }
        .thenByDescending { views(it).lastOpenedAt ?: Long.MIN_VALUE }
        .then(byName)
    SortType.LEAST_VIEWED -> compareBy<T> { views(it).openCount }.then(byName)
    SortType.RECENTLY_VIEWED -> compareByDescending<T> { views(it).lastOpenedAt ?: Long.MIN_VALUE }
        .then(byName)
    SortType.MOST_DAYS_VIEWED -> compareByDescending<T> { views(it).daysViewed }
        .thenByDescending { views(it).lastOpenedAt ?: Long.MIN_VALUE }
        .then(byName)
    SortType.MOST_WATCHED -> compareByDescending<T> { views(it).watchedSeconds }
        .thenByDescending { views(it).lastOpenedAt ?: Long.MIN_VALUE }
        .then(byName)
    else -> null
}

/**
 * The tag list's order: by what the list shows, ignoring case, so `female/glasses` and
 * `Smile` sit where a reader looks for them rather than after every capital. Tags have no
 * modified time, so the two time orders fall back to the name. The view orders sum the
 * files each entry lists or contains ([views], keyed by canonical, else tag).
 */
internal fun tagComparator(
    sortType: SortType,
    views: Map<String, ViewTotals> = emptyMap(),
): Comparator<LocalTagWithMetadata> {
    val byName = compareBy<LocalTagWithMetadata, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
    viewComparator(sortType, { views[it.canonical ?: it.tag] ?: ViewTotals.NONE }, byName)
        ?.let { return it }
    return when (sortType) {
        SortType.NAME_DESC -> byName.reversed()
        else -> byName
    }
}

/** The file listing's order. [views] is keyed by [LocalFile.fullPath]. */
internal fun fileComparator(
    sortType: SortType,
    views: Map<String, ViewTotals> = emptyMap(),
): Comparator<LocalFile> {
    val byName = compareBy<LocalFile> { it.filename }
    viewComparator(sortType, { views[it.fullPath()] ?: ViewTotals.NONE }, byName)
        ?.let { return it }
    // Two files modified in the same instant (a gallery downloaded in one go often is) fall
    // back to the name, so the time orders do not shuffle between listings.
    return when (sortType) {
        SortType.NAME_DESC -> byName.reversed()
        SortType.LAST_MODIFIED_ASC -> compareBy<LocalFile> { it.lastModified }.then(byName)
        SortType.LAST_MODIFIED_DESC ->
            compareByDescending<LocalFile> { it.lastModified }.then(byName)
        else -> byName
    }
}

enum class ViewType {
    GRID,
    LIST
}

typealias ThumbnailsMap = Map<String, List<String>>

/** How long a folder that failed to list for its cover waits before it is tried again. */
private const val COVER_RETRY_MILLIS = 30_000L

/** Unique-work name for the tag scan, shared by the enqueue and the observer that follows it. */
private const val SCAN_TAGS_WORK = "scanTags"

class ExplorerViewModel(
    application: Application,
    private val fileRepository: FileRepository,
    private val serverRepository: ServerRepository,
    private val connectionRepository: ConnectionRepository,
    private val tagRepository: TagRepository,
    private val settingsRepository: SettingsRepository,
    // No default: one built here would resolve its cache directory from nothing and quietly
    // disagree with the one the server is writing to.
    private val thumbnailManager: ThumbnailManager,
    private val pairingRepository: PairingRepository,
    private val viewRecorder: ViewRecorder,
) : AndroidViewModel(application) {
    private val _path = MutableStateFlow("")
    val pathState: StateFlow<String> = _path.asStateFlow()
    private val _navigationStack = MutableStateFlow(listOf(""))
    val navigationStackState: StateFlow<List<String>> = _navigationStack.asStateFlow()

    private val _list: MutableStateFlow<Map<String, List<LocalFile>>> = MutableStateFlow(emptyMap())
    val listState: StateFlow<Map<String, List<LocalFile>>> = _list.asStateFlow()

    private val _connection: MutableStateFlow<Connection?> = MutableStateFlow(null)
    val connectionState: StateFlow<Connection?> = _connection.asStateFlow()
    private val _connections: MutableStateFlow<List<Connection>> = MutableStateFlow(emptyList())
    val connectionsState: StateFlow<List<Connection>> = _connections.asStateFlow()
    private val _connectionName = MutableStateFlow("")
    val connectionNameState = _connectionName.asStateFlow()

    private val _toast = MutableSharedFlow<String>()
    val toast = _toast.asSharedFlow()

    private val _thumbnails: MutableStateFlow<ThumbnailsMap> =
        MutableStateFlow(emptyMap())
    val thumbnailsState = _thumbnails.asStateFlow()

    private val _tagThumbnails: MutableStateFlow<ThumbnailsMap> =
        MutableStateFlow(emptyMap())
    val tagThumbnailsState = _tagThumbnails.asStateFlow()

    // Declared before init: Kotlin initialises properties and init blocks in declaration
    // order, and the tag observer in init reaches for this.
    private val workManager = WorkManager.getInstance(application.applicationContext)

    // On init: start the http server, find connections, and start following the tag scan.
    init {
        viewModelScope.launch { serverRepository.startup() }
        // Next to the server on purpose: connect() waits for the first read of this, so a
        // missing call here would leave every connection hanging on open.
        pairingRepository.follow(viewModelScope)
        viewModelScope.launch {
            connectionRepository.getConnections().collect { connections ->
                _connections.update { connections }
            }
        }
        // Tags used to be read exactly once here and never again, so a tag discovered by
        // ScanTagsWorker stayed invisible for the rest of the session — browse a folder of
        // .xmp sidecars, open Tags, see nothing, restart the app, see everything. Following
        // the worker instead means the one read covers both the initial load and every
        // later scan: the flow emits the current state as soon as it is collected, so there
        // is no separate startup fetch to keep in step.
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(SCAN_TAGS_WORK)
                // Any TERMINAL state, not just SUCCEEDED. TagRepository.scanTags writes each
                // file's tags as it walks the batch, so a scan that throws partway — a
                // malformed sidecar, the share going away mid-walk — has already committed
                // rows before returning Result.failure(). Counting only SUCCEEDED would
                // leave those invisible until the next successful scan, which is precisely
                // the staleness this observer exists to remove. Same for CANCELLED.
                //
                // The distinct is what keeps this cheap: without it every intermediate
                // transition (ENQUEUED, RUNNING) would re-read the tags and re-fetch every
                // tag thumbnail for nothing.
                .map { infos -> infos.count { it.state.isFinished } }
                .distinctUntilChanged()
                .collect { refreshTags() }
        }
    }

    private suspend fun refreshTags() {
        val tags = tagRepository.getTags()
        // Aliases arrive through the same scan, so they are re-read with the tags.
        _tagSearch.update { tagRepository.getTagSearch() }
        _tags.update { tags }
        _tagThumbnails.update { fetchTagThumbnails(tags) }
    }

    fun connect(connection: Connection) {  // stack-reviewer-ignore: resetting before the connect is the point. The stack and path belong to the previous connection, so keeping them when the new one fails to open would leave that failed connection showing a path from somewhere else, which is the bug this change fixes.
        abandonRefreshRequest()
        _navigationStack.update { listOf("") }
        _path.update { "" }
        viewModelScope.launch {
            try {
                _connection.update { connection }
                _list.update { emptyMap() }
                _thumbnails.update { emptyMap() }
                _tagIndexIsPartial.update {
                    !tagRepository.capabilitiesFor(connection.id).indexesUnvisitedDirectories
                }

                fileRepository.connect(connection)

                pairingRepository.awaitLoaded()
                followWantedCovers(connection.id)
                listFiles(connection.id)
                _tagThumbnails.update { fetchTagThumbnails(_tags.value) }
            } catch (e: AllFilesAccessMissing) {
                _toast.emit("Allow all files access to open ${connection.name}")
            } catch (e: Exception) {
                _toast.emit(
                    when (connection.type) {
                        ConnectionType.SMB -> "Unable to connect to ${connection.hostname}"
                        ConnectionType.LOCAL ->
                            "Unable to open ${connection.name}: ${e.message ?: "unknown error"}"
                    }
                )
            }
        }

        viewModelScope.launch {
            _connectionName.update {
                when (connection.type) {
                    ConnectionType.SMB -> connection.name + " » " + connection.share
                    ConnectionType.LOCAL -> connection.name
                }
            }
        }
    }

    fun testConnection(connection: Connection) {
        viewModelScope.launch {
            try {
                fileRepository.connect(connection)
                _toast.emit(
                    when (connection.type) {
                        ConnectionType.SMB -> "Connected to ${connection.hostname}"
                        ConnectionType.LOCAL -> "Opened ${connection.localRoot}"
                    }
                )
            } catch (e: Exception) {
                _toast.emit(e.message ?: "Unable to connect to ${connection.hostname}")
            }
        }
    }

    fun changePath(path: String) {
        abandonRefreshRequest()
        _path.update { path }
        _navigationStack.update { it.plus(path) }
    }

    fun goBack() {
        abandonRefreshRequest()
        _navigationStack.update { it.dropLast(1) }
        _path.update { _navigationStack.value.lastOrNull().orEmpty() }
    }

    /**
     * Drops a pending refresh when we leave the directory it was made in.
     *
     * Without this, a refresh interrupted by navigating away stayed pending — and because
     * the request is matched on path, it would fire later, forcing an unasked-for round trip
     * the moment that directory was opened again. Leaving the directory abandons it.
     */
    private fun abandonRefreshRequest() {
        _refreshRequest.update { null }
        _isRefreshing.update { false }
    }

    fun getUrl(path: String, thumbnail: Boolean = false): String {
        val connectionId =
            _connection.value?.id
                ?: throw IllegalStateException(
                    "Constructing a URL when connectionId is null"
                )
        // Canonical before it becomes a URL, because the URL is Sketch's cache key: a local
        // copy has to ask for its remote twin's URL to share its cache entry. The server
        // reads the local copy anyway, when there is one.
        val canonical = pairingRepository.current().canonicalize(connectionId, path)
        return constructUrl(canonical.connectionId, canonical.path, thumbnail)
    }

    private val _search: MutableStateFlow<String> = MutableStateFlow("")
    val searchState: StateFlow<String> = _search.asStateFlow()
    fun search(query: String) {
        _search.update { query }
    }

    private val _filter = MutableStateFlow<List<IconType>>(emptyList())
    val filterState: StateFlow<List<IconType>> = _filter.asStateFlow()
    fun filter(extensions: List<IconType>) {
        _filter.update { extensions }
    }

    private val _sort = MutableStateFlow(SortType.NAME_ASC)
    val sortState: StateFlow<SortType> = _sort.asStateFlow()
    fun sort(sortType: SortType) {
        _sort.update { sortType }
    }

    // The collector that watches path/search/refresh. Held so a second call to listFiles
    // replaces it rather than adding another: reconnecting while one is already running
    // would otherwise leave both alive, each reacting to every later path change.
    //
    // A refresh no longer restarts it at all — that is what used to make a single
    // pull-to-refresh leak into every directory opened afterwards. It goes through
    // _refreshRequest instead.
    private var listFilesCollector: Job? = null

    /**
     * A pull-to-refresh, addressed to the directory it was made in.
     *
     * [token] exists so that refreshing the same directory twice produces a value the
     * StateFlow considers new; without it the second refresh would be dropped as a duplicate.
     */
    private data class RefreshRequest(val path: String, val token: Long)

    private val _refreshRequest = MutableStateFlow<RefreshRequest?>(null)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshingState: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * The connection the listing collector is browsing — what tagged rows are framed for.
     *
     * Not [connectionState]: editing a connection points that at the one being edited
     * without browsing it, and a tag scan finishing meanwhile would frame the tag
     * thumbnails for a connection nothing is listing.
     */
    @Volatile
    private var browsingConnectionId: Int? = null

    private fun listFiles(connectionId: Int) {
        browsingConnectionId = connectionId
        listFilesCollector?.cancel()
        listFilesCollector = viewModelScope.launch(Dispatchers.IO) {
            combine(pathState, searchState, _refreshRequest) { path, search, request ->
                Triple(path, search, request)
            }.collectLatest { (path, search, request) ->
                // collectLatest cancels the previous body when a new value arrives, which is
                // what the hand-rolled job juggling here was approximating.
                //
                // Anything thrown in here reaches viewModelScope and takes the process with
                // it, and SMB throws for entirely ordinary reasons — the NAS asleep, a folder
                // renamed on the server, credentials rotated. Listing a directory that no
                // longer exists was a hard crash (STATUS_OBJECT_NAME_NOT_FOUND), so surface
                // it the way connect() already surfaces a failed connection.
                try {
                    if (path.startsWith(tagPrefix)) {
                        val tag = path.substringAfter(tagPrefix)
                        // Tagged rows are the share's (a local root indexes nothing), so
                        // they are put in the frame of what is being browsed before the
                        // grid builds URLs or opens folders from their paths.
                        val files = pairingRepository.current().inFrameOf(
                            connectionId,
                            fileRepository.getFilesWithTag(LocalTag(tag)),
                        )
                        _list.update { it.plus(path to files.withThumbnailEligibility()) }
                        fetchThumbnails(connectionId, files)
                        // A tag listing is assembled from rows already in the database, so
                        // there is nothing for a refresh to wait on.
                        _isRefreshing.update { false }
                        return@collectLatest
                    }

                    // The refresh applies to the directory it was requested in and nowhere
                    // else. This used to be a flag captured by the collector itself, so a
                    // single pull-to-refresh made *every* directory opened afterwards skip
                    // the cache and go to the share, for the life of the collector.
                    val refresh = if (request?.path == path) {
                        RefreshMode.FORCE
                    } else {
                        RefreshMode.IF_STALE
                    }

                    fileRepository.listFiles(connectionId, path, search, refresh)
                        .collect { listing ->
                            _list.update {
                                it.plus(path to listing.files.withThumbnailEligibility())
                            }
                            _isRefreshing.update { listing.syncing }

                            fetchThumbnails(connectionId, listing.files)
//                          thumbnailsJob(connectionId, listing.files)
                            if (!listing.syncing) {
                                scanTagsJob(connectionId, refresh == RefreshMode.FORCE)
                            }
                        }

                    // Consumed. Clearing it means returning to this directory later goes
                    // back through the TTL rather than forcing a second round trip, and the
                    // identity check avoids swallowing a refresh requested in the meantime.
                    _refreshRequest.update { if (it === request) null else it }
                } catch (e: CancellationException) {
                    throw e // collectLatest cancels this body on every new value; not an error.
                } catch (e: Exception) {
                    Log.e("ExplorerViewModel", "Failed to list '$path'", e)
                    _isRefreshing.update { false }
                    _toast.emit(
                        "Could not open ${path.ifEmpty { "the share" }}: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    private fun List<LocalFile>.withThumbnailEligibility(): List<LocalFile> = map {
        it.copy(canHaveThumbnail = Utils.canHaveThumbnail(it.filename))
    }

    fun forceRefresh() {
        if (connectionState.value == null) return
        _isRefreshing.update { true }
        _refreshRequest.update {
            RefreshRequest(path = _path.value, token = (it?.token ?: 0) + 1)
        }
    }

    fun getSortFunction(sortType: SortType, views: Map<String, ViewTotals> = emptyMap()): Comparator<LocalFile> =
        fileComparator(sortType, views)

    /**
     * An open, in the browsing connection's frame: an image held in the viewer past the
     * dwell threshold, or a file handed to another app. Named canonically before it is
     * written, so the share and its Syncthing copy are one record.
     */
    fun recordOpen(file: LocalFile) {
        val canonical = pairingRepository.current().canonicalize(file.connectionId, file.fullPath())
        viewModelScope.launch(Dispatchers.IO) {
            try {
                viewRecorder.recordOpen(canonical)
            } catch (e: Exception) {
                Log.w("ExplorerViewModel", "Could not record opening ${file.fullPath()}", e)
            }
        }
    }

    fun addConnection(connection: Connection) {
        viewModelScope.launch {
            connectionRepository.addConnection(connection)
            _connections.update { it.plus(connection) }
            _connection.update { connection }
        }
    }

    val pairingsState: StateFlow<List<Pairing>> = pairingRepository.pairings

    /**
     * The list on screen follows the table, so a write that fails shows as nothing having
     * changed — but the failure itself would otherwise escape viewModelScope and take the
     * process with it. A connection deleted a moment ago is the likely one: the pairing's
     * foreign key refuses it.
     */
    fun savePairing(pairing: Pairing) {
        viewModelScope.launch {
            try {
                pairingRepository.save(pairing)
            } catch (e: Exception) {
                Log.e("ExplorerViewModel", "Could not save $pairing", e)
                _toast.emit("Could not save the pairing: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun deletePairing(pairing: Pairing) {
        viewModelScope.launch {
            try {
                pairingRepository.delete(pairing)
            } catch (e: Exception) {
                Log.e("ExplorerViewModel", "Could not remove $pairing", e)
                _toast.emit("Could not remove the pairing: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun editConnection(connection: Connection?) {
        viewModelScope.launch { _connection.update { connection } }
    }

    fun deleteConnection(connection: Connection) {
        viewModelScope.launch {
            connectionRepository.deleteConnection(connection)
            _connections.update { it.minus(connection) }
        }
    }

    private val _browsableImages = MutableStateFlow(emptyList<LocalFile>())
    val browsableImagesState = _browsableImages.asStateFlow()
    private val _initialBrowsableImage = MutableStateFlow(0)
    val initialBrowsableImageState = _initialBrowsableImage.asStateFlow()

    fun browseImages(browsableImages: List<LocalFile>, initialBrowsableImage: Int) {
        viewModelScope.launch {
            _browsableImages.update { browsableImages }
            _initialBrowsableImage.update { initialBrowsableImage }
        }
    }

    private val _tags = MutableStateFlow(emptyList<LocalTagWithMetadata>())
    val tagsState = _tags.asStateFlow()

    private val _tagSearch = MutableStateFlow(TagSearch.NONE)
    private val _tagQuery = MutableStateFlow("")
    val tagQueryState: StateFlow<String> = _tagQuery.asStateFlow()

    /**
     * The tag list narrowed to [tagQueryState]. Its own query, not [searchState]: that one
     * drives the file listing, so typing here would re-list whatever directory was last open.
     */
    /**
     * The tag list's own order. Not [sortState]: that one orders the file listing, so the
     * Tags screen's sort button used to flip the next directory's order and leave this list
     * exactly as the query returned it.
     */
    private val _tagSort = MutableStateFlow(SortType.NAME_ASC)
    val tagSortState: StateFlow<SortType> = _tagSort.asStateFlow()

    fun sortTags(sortType: SortType) {
        _tagSort.update { sortType }
    }

    /**
     * Each tag-list entry's view totals, read only while the tag list is in a view order,
     * and again after every recorded view.
     */
    private val tagViews: StateFlow<Map<String, ViewTotals>> =
        combine(_tagSort, viewRecorder.changes) { sort, _ -> sort }
            .map { sort ->
                if (!sort.usesViews) emptyMap() else try {
                    viewRecorder.tagTotals()
                } catch (e: Exception) {
                    Log.w("ExplorerViewModel", "Could not read tag view totals", e)
                    emptyMap()
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val visibleTagsState: StateFlow<List<LocalTagWithMetadata>> =
        combine(_tags, _tagSearch, _tagQuery, _tagSort, tagViews) { tags, search, query, sort, views ->
            val matching =
                if (query.isBlank()) tags else tags.filter { search.matches(it.tag, query, it.label) }
            matching.sortedWith(tagComparator(sort, views))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun searchTags(query: String) {
        _tagQuery.update { query }
    }

    /**
     * What the header names an open tag: its group's label rather than the member the path
     * goes through (`characters/hagoromo gitsune`, not `character:hagoromo gitsune`). Null
     * when the path is not a tag.
     */
    val openTagLabelState: StateFlow<String?> =
        combine(_path, _tags) { path, tags ->
            if (!path.startsWith(tagPrefix)) return@combine null
            val tag = path.substringAfter(tagPrefix)
            tags.firstOrNull { it.tag == tag }?.label ?: tag
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the tag list covers only what has been browsed.
     *
     * True for sidecars, which are discovered by listing the directory holding them — so the
     * Tags screen has always under-reported, with nothing on screen admitting it.
     */
    private val _tagIndexIsPartial = MutableStateFlow(false)
    val tagIndexIsPartialState: StateFlow<Boolean> = _tagIndexIsPartial.asStateFlow()

    fun getFilesWithTag(tag: LocalTag) {
        viewModelScope.launch {
            abandonRefreshRequest()
            _path.update { "${tagPrefix}${tag.tag}" }
        }
    }

    /**
     * The grid's column count, as stored. One value, written by both the grid's picker and
     * Settings, and followed rather than read once: it used to be read a single time at
     * startup, so a change in Settings did nothing until a restart, and a pick from the
     * toolbar lasted only the session (#71).
     */
    val columnCountState: StateFlow<Int> =
        settingsRepository.columnCount()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsEntity().defaultColumnCount)

    fun setColumnCount(columnCount: Int) {
        viewModelScope.launch { settingsRepository.setColumnCount(columnCount) }
    }

    fun getFileSize(connectionId: Int, fullPath: String): Long {
        return fileRepository.getFileSize(connectionId, fullPath)
    }

    private val _tagScreenViewType = MutableStateFlow(ViewType.GRID)
    val tagScreenViewTypeState = _tagScreenViewType.asStateFlow()

    fun setTagScreenViewType(viewType: ViewType) {
        _tagScreenViewType.update { viewType }
    }

    private fun scanTagsJob(connectionId: Int, forceRemote: Boolean) {
        val workRequest =
            OneTimeWorkRequestBuilder<ScanTagsWorker>()
                .setInputData(
                    workDataOf(
                        "CONNECTION_ID" to connectionId,
                        "FORCE_REMOTE" to forceRemote,
                    )
                )
                .build()

        workManager.enqueueUniqueWork(SCAN_TAGS_WORK, ExistingWorkPolicy.KEEP, workRequest)
    }

    /**
     * A file is its own thumbnail, so those are known the moment the listing is.
     *
     * Folders are not handled here any more. Their covers used to be read for every folder
     * in the listing, one query each, before any of them could paint — thousands for a
     * directory of galleries, most of them nowhere near the screen. They are asked for as
     * they come into view instead; see [requestCovers].
     */
    private fun fetchThumbnails(connectionId: Int, files: List<LocalFile>) {
        val thumbnailsMap = files
            .filter { it.type != FileType.FOLDER }
            .associate { it.fullPath() to listOf(it.fullPath()) }
        if (thumbnailsMap.isEmpty()) return
        _thumbnails.update { it.plus(thumbnailsMap) }
    }

    /** Folders the grid has on screen or about to be, most wanted first. */
    private val _wantedCovers = MutableStateFlow<List<String>>(emptyList())

    /**
     * Folders whose cover has been settled this connection, found or not, so scrolling
     * back over them costs nothing.
     */
    private val coversSettled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When a folder last failed to list for its cover; see [loadCover]. */
    private val coverFailedAt = ConcurrentHashMap<String, Long>()

    private var coverLoader: Job? = null

    /**
     * Called by the grid with the folders on screen, then those just past it.
     *
     * Visibility is what decides the order, because the order is what the user sees: the
     * first screen fills first, and folders scrolled away are dropped before they cost a
     * listing — the new set replaces the old one, and anything still waiting for a slot is
     * cancelled with it.
     */
    fun requestCovers(folderPaths: List<String>) {
        _wantedCovers.update { folderPaths }
    }

    private fun followWantedCovers(connectionId: Int) {
        coversSettled.clear()
        coverFailedAt.clear()
        coverLoader?.cancel()
        coverLoader = viewModelScope.launch(Dispatchers.IO) {
            _wantedCovers.collectLatest { wanted ->
                coroutineScope {
                    val now = System.currentTimeMillis()
                    wanted
                        .filter { it !in coversSettled }
                        .filter { path ->
                            coverFailedAt[path]?.let { now - it >= COVER_RETRY_MILLIS } ?: true
                        }
                        .forEach { path ->
                            launch { loadCover(connectionId, path) }
                        }
                }
            }
        }
    }

    private suspend fun loadCover(connectionId: Int, path: String) {
        try {
            val startedAt = System.currentTimeMillis()
            val cover = fileRepository.folderCover(connectionId, path)
            coversSettled += path
            Log.d(
                "ExplorerViewModel",
                "Cover for $path: ${cover.size} in ${System.currentTimeMillis() - startedAt}ms"
            )
            if (cover.isNotEmpty()) _thumbnails.update { it.plus(path to cover) }
        } catch (e: CancellationException) {
            // Scrolled away before it got a slot. Not settled, so it is asked again when it
            // comes back — and a listing that had already started has written its rows by
            // then, so the second ask is a database read.
            throw e
        } catch (e: Exception) {
            // Not settled, since the share may only have hiccuped — but not retried on
            // every scroll either: a folder it will not list waits out a back-off before it
            // is asked again. Opening it reports the error properly.
            coverFailedAt[path] = System.currentTimeMillis()
            Log.w("ExplorerViewModel", "Could not list '$path' for its cover", e)
        }
    }

    private fun thumbnailsJob(connectionId: Int, files: List<LocalFile>) {
        viewModelScope.launch {
            // Load already usable thumbnails
            val ready = files.filter { it.thumbnailStatus == ThumbnailStatus.SUCCESS }
                .associate { it.fullPath() to listOf(it.fullPath()) }
            val folders = files.filter { it.type == FileType.FOLDER }
                .associate {
                    it.fullPath() to fileRepository.getProcessedFolderThumbnails(
                        connectionId,
                        it.fullPath()
                    )
                }
            _thumbnails.update {
                it.plus(ready).plus(folders)
            }

            // Process pending thumbnails in the background
            val toProcess = files.filter { it.thumbnailStatus == ThumbnailStatus.UNPROCESSED }
                .associate { it.fullPath() to listOf(it.fullPath()) }

            val processedFiles = thumbnailManager.queueBatchThumbnailGeneration(
                connectionId,
                toProcess
            )

            if (processedFiles.isNotEmpty()) {
                _thumbnails.update {
                    it.plus(processedFiles)
                }
            } else {
                delay(1000)
                thumbnailsJob(connectionId, files)
            }
        }
    }

    /**
     * View totals for the directory on screen, keyed by [LocalFile.fullPath]: a file's own,
     * a folder's summed over what is directly inside it. Read only while the listing is in
     * a view order, and again after every recorded view, so a sort by views follows what
     * was just opened.
     */
    val listingViewsState: StateFlow<Map<String, ViewTotals>> =
        combine(_list, _path, _sort, viewRecorder.changes) { list, path, sort, _ ->
            Triple(list[path].orEmpty(), sort, browsingConnectionId)
        }
            .map { (files, sort, connectionId) ->
                if (!sort.usesViews || connectionId == null || files.isEmpty()) {
                    emptyMap()
                } else try {
                    viewRecorder.totalsFor(connectionId, files, pairingRepository.current())
                } catch (e: Exception) {
                    Log.w("ExplorerViewModel", "Could not read view totals", e)
                    emptyMap()
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private suspend fun fetchTagThumbnails(
        tags: List<LocalTagWithMetadata>
    ): Map<String, List<String>> {
        val thumbnails =
            tags.associate {
                val tagIdentifier = "${tagPrefix}${it.tag}"
                if (it.count > 0) {
                    val rows = fileRepository.getTagThumbnails(LocalTag(it.tag), 4)
                    val framed = browsingConnectionId
                        ?.let { id -> pairingRepository.current().inFrameOf(id, rows) }
                        ?: rows
                    tagIdentifier to framed.map { file -> file.fullPath() }
                } else {
                    tagIdentifier to emptyList()
                }
            }
        return thumbnails
    }
}