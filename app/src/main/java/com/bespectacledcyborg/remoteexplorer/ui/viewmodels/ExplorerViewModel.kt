package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import android.app.Application
import android.util.Log
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bespectacledcyborg.remoteexplorer.data.Caption
import com.bespectacledcyborg.remoteexplorer.data.CaptionRepository
import com.bespectacledcyborg.remoteexplorer.data.ConnectionRepository
import com.bespectacledcyborg.remoteexplorer.data.CoverOrder
import com.bespectacledcyborg.remoteexplorer.data.CoverPreferences
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.data.FileRepository
import com.bespectacledcyborg.remoteexplorer.data.LastLocation
import com.bespectacledcyborg.remoteexplorer.data.PairingRepository
import com.bespectacledcyborg.remoteexplorer.data.RefreshMode
import com.bespectacledcyborg.remoteexplorer.data.ServerRepository
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import com.bespectacledcyborg.remoteexplorer.data.GroupMode
import com.bespectacledcyborg.remoteexplorer.data.GroupModes
import com.bespectacledcyborg.remoteexplorer.data.TagRepository
import com.bespectacledcyborg.remoteexplorer.data.TagSearch
import com.bespectacledcyborg.remoteexplorer.data.displayLabels
import com.bespectacledcyborg.remoteexplorer.data.listedChips
import com.bespectacledcyborg.remoteexplorer.data.TagChip
import com.bespectacledcyborg.remoteexplorer.data.tagChips
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.AllFilesAccessMissing
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetric
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetrics
import com.bespectacledcyborg.remoteexplorer.data.db.AnnotatedDirectory
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
import com.bespectacledcyborg.remoteexplorer.data.isPseudoFolder
import com.bespectacledcyborg.remoteexplorer.data.createdAt
import com.bespectacledcyborg.remoteexplorer.data.newestAdditionsPath
import com.bespectacledcyborg.remoteexplorer.data.tasks.ScanTagsWorker
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.NaturalOrder
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.bespectacledcyborg.remoteexplorer.utils.Utils.constructUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import com.bespectacledcyborg.remoteexplorer.data.FolderSortChoice
import com.bespectacledcyborg.remoteexplorer.data.FolderSortRepository
import com.bespectacledcyborg.remoteexplorer.data.FolderSorts
import com.bespectacledcyborg.remoteexplorer.data.GridColumns

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

internal fun tagComparator(
    sortType: SortType,
    views: Map<String, ViewTotals> = emptyMap(),
    shown: Map<String, String> = emptyMap(),
): Comparator<LocalTagWithMetadata> {
    val byName = compareBy<LocalTagWithMetadata, String>(NaturalOrder) {
        shown[it.tag] ?: it.label
    }
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
    created: Map<String, Date> = emptyMap(),
): Comparator<LocalFile> {
    val byName = compareBy<LocalFile, String>(NaturalOrder) { it.filename }
    viewComparator(sortType, { views[it.fullPath()] ?: ViewTotals.NONE }, byName)
        ?.let { return it }
    return when (sortType) {
        SortType.NAME_DESC -> byName.reversed()
        SortType.LAST_MODIFIED_DESC ->
            compareByDescending<LocalFile> { it.lastModified }.then(byName)
        SortType.ADDED_DESC ->
            compareByDescending<LocalFile> { it.createdAt(created) }.then(byName)
        else -> byName
    }
}

enum class ViewType {
    GRID,
    LIST
}

sealed interface ConnectionTest {
    data object Running : ConnectionTest
    data class Passed(val message: String) : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

typealias ThumbnailsMap = Map<String, List<String>>

/** How long a folder that failed to list for its cover waits before it is tried again. */
private const val COVER_RETRY_MILLIS = 30_000L

data class ListingCreated(val listing: List<LocalFile>, val byPath: Map<String, Date>) {
    fun readyFor(folder: List<LocalFile>, sort: SortType, path: String): Boolean =
        !needsCreatedDates(sort, path) || listing == folder

    companion object {
        val NONE = ListingCreated(emptyList(), emptyMap())
    }
}

internal fun needsCreatedDates(sort: SortType, path: String): Boolean =
    sort == SortType.ADDED_DESC || path == newestAdditionsPath

internal fun coverOrderFor(sortType: SortType): CoverOrder = when (sortType) {
    SortType.NAME_DESC -> CoverOrder.NameDesc
    SortType.LAST_MODIFIED_DESC, SortType.ADDED_DESC -> CoverOrder.Newest
    else -> CoverOrder.Name
}

internal fun listingsBucket(listings: Int): String = when {
    listings == 0 -> "0"
    listings <= 3 -> "1-3"
    else -> "4+"
}

internal fun depthOf(path: String): String =
    when (val depth = path.split('/').count { it.isNotEmpty() }) {
        in 0..3 -> depth.toString()
        else -> "4+"
    }

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
    private val captionRepository: CaptionRepository,
    private val coverPreferences: CoverPreferences,
    private val folderSortRepository: FolderSortRepository,
    private val lastLocation: LastLocation,
    private val groupModes: GroupModes,
    private val metrics: LoadMetrics = LoadMetrics.NONE,
    private val sourceKind: (Int) -> String = { "unknown" },
) : AndroidViewModel(application) {
    private val _path = MutableStateFlow("")
    val pathState: StateFlow<String> = _path.asStateFlow()
    private val _navigationStack = MutableStateFlow(listOf(""))
    val navigationStackState: StateFlow<List<String>> = _navigationStack.asStateFlow()
    private val scrollPositions = ScrollPositions()

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

    private val _captioned = MutableStateFlow(emptySet<AnnotatedDirectory>())

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
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(SCAN_TAGS_WORK)
                .map { infos -> infos.count { it.state.isFinished } }
                .distinctUntilChanged()
                .collect { refreshTags() }
        }
    }

    private suspend fun refreshTags() {
        val tags = tagRepository.getTags()
        _tagSearch.update { tagRepository.getTagSearch() }
        _tags.update { tags }
        _tagsRefreshed.update { it + 1 }
        _tagThumbnails.update { fetchTagThumbnails(tags) }
        _captioned.update {
            try {
                captionRepository.captionedDirectories()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ExplorerViewModel", "Could not read captioned directories", e)
                it
            }
        }
    }

    fun connect(connection: Connection) {  // stack-reviewer-ignore: resetting before the connect is the point. The stack and path belong to the previous connection, so keeping them when the new one fails to open would leave that failed connection showing a path from somewhere else, which is the bug this change fixes.
        abandonRefreshRequest()
        _navigationStack.update { listOf("") }
        galleryRun = null
        scrollPositions.reset()
        _path.update { "" }
        locationConnectionId = connection.id
        rememberLocation()  // stack-reviewer-ignore: remembering the connection the user just chose, even one that fails to open, is intended; the next launch retrying it is what you want when the NAS was asleep
        viewModelScope.launch {
            try {
                _connection.update { connection }
                _list.update { emptyMap() }
                _thumbnails.update { emptyMap() }

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

    private val _connectionTest = MutableStateFlow<ConnectionTest?>(null)
    val connectionTestState: StateFlow<ConnectionTest?> = _connectionTest.asStateFlow()
    private var connectionTestJob: Job? = null

    fun testConnection(connection: Connection) {
        connectionTestJob?.cancel()
        _connectionTest.update { ConnectionTest.Running }
        connectionTestJob = viewModelScope.launch {
            val result = try {
                fileRepository.connect(connection)
                ConnectionTest.Passed(
                    when (connection.type) {
                        ConnectionType.SMB -> "Connected to ${connection.hostname}"
                        ConnectionType.LOCAL -> "Opened ${connection.localRoot}"
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionTest.Failed(e.message ?: "Unable to connect to ${connection.hostname}")
            }
            _connectionTest.update { result }
        }
    }

    private fun clearConnectionTest() {
        connectionTestJob?.cancel()
        _connectionTest.update { null }
    }

    fun changePath(path: String) {
        abandonRefreshRequest()
        scrollPositions.push()  // stack-reviewer-ignore: _navigationStack changes in exactly four places, connect, changePath, swipeGallery and goBack, and each moves scrollPositions in step with it
        _path.update { path }
        _navigationStack.update { it.plus(path) }
        rememberLocation()
    }

    private var galleryRun: GalleryRun? = null

    fun openFolder(path: String, listing: List<LocalFile>) {
        galleryRun = GalleryRun(
            origin = _path.value,
            galleries = listing.filter { it.type == FileType.FOLDER }.map { it.fullPath() },
        )
        changePath(path)
    }

    fun swipeGallery(step: SwipeStep) {
        val run = galleryRun ?: return
        if (!run.isOpenFrom(_navigationStack.value) || !folderSortState.value.isLeaf) return
        val next = run.neighbour(_path.value, step) ?: return
        abandonRefreshRequest()
        scrollPositions.recordTop(ScrollPosition())  // stack-reviewer-ignore: recordTop replaces the top entry in place (ScrollPositions.kt), matching the stack's dropLast(1) + next, so both stay the same depth and Back restores the listing's position
        _path.update { next }
        _navigationStack.update { it.dropLast(1) + next }
        rememberLocation()
    }

    fun goBack() {
        abandonRefreshRequest()
        scrollPositions.pop()
        _navigationStack.update { it.dropLast(1) }
        _path.update { _navigationStack.value.lastOrNull().orEmpty() }
        rememberLocation()
    }

    private var locationConnectionId: Int? = null
    private var restoreAttempted = false

    private fun rememberLocation() {
        val connectionId = locationConnectionId ?: return
        lastLocation.remember(connectionId, _path.value)
    }

    suspend fun restoreLastLocation(): Boolean {
        if (restoreAttempted) return false
        restoreAttempted = true
        val connections = connectionRepository.getConnections().first()
        val location = withContext(Dispatchers.IO) { lastLocation.resolve(connections) } ?: return false
        if (locationConnectionId != null) return false  // stack-reviewer-ignore: it sits after the suspending resolve and before connect, so it fires exactly when the user opened a connection while the restore was reading, and the restore then stands down instead of clobbering it
        connect(location.connection)
        location.folderStack.drop(1).forEach(::changePath)
        return true
    }

    fun leaveConnection() {
        locationConnectionId = null
        lastLocation.forget()
    }

    fun scrollPositionToRestore(): ScrollPosition = scrollPositions.top

    fun recordScrollPosition(path: String, position: ScrollPosition) {
        if (path == _path.value) scrollPositions.recordTop(position)  // stack-reviewer-ignore: dropping a late report after the path changed is the point; positions are stored per stack entry, so it would land on the new folder's entry, and the folder being left already recorded every position up to the change
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
        val canonical = pairingRepository.current().canonicalize(connectionId, path)
        return constructUrl(
            canonical.connectionId, canonical.path, serverRepository.urlToken, thumbnail,
        )
    }

    private val _search: MutableStateFlow<String> = MutableStateFlow("")
    val searchState: StateFlow<String> = _search.asStateFlow()
    fun search(query: String) {
        _search.update { query }
    }

    private val _searchCleared = MutableStateFlow(0)
    val searchClearedState: StateFlow<Int> = _searchCleared.asStateFlow()

    fun clearSearch() {  // stack-reviewer-ignore: #151 scopes the reset to opening a tag; a search carrying across folders is existing behaviour and the breadcrumb shows it, and seeding the bar from searchState makes it show that active filter instead of an empty field
        _search.update { "" }
        _searchCleared.update { it + 1 }
    }

    private val _filter = MutableStateFlow<List<IconType>>(emptyList())
    val filterState: StateFlow<List<IconType>> = _filter.asStateFlow()
    fun filter(extensions: List<IconType>) {
        _filter.update { extensions }
    }

    private val currentListingHasSubfolders: Flow<Boolean> =
        combine(_list, _path) { list, path ->
            list[path]?.any { it.type == FileType.FOLDER } ?: true  // stack-reviewer-ignore: true means "has subfolders", i.e. non-leaf, which is what the PR body says; defaulting to leaf would flip every series folder to A-Z then back instead, and most opens are galleries served from Room before the first frame
        }.distinctUntilChanged()

    private val folderSorts: StateFlow<FolderSorts> =
        combine(
            folderSortRepository.observeAll(),
            settingsRepository.defaultSort(),
            pairingRepository.pairings,
        ) { rows, default, _ -> FolderSorts(rows, default, pairingRepository.current()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, FolderSorts.NONE)

    val folderSortState: StateFlow<FolderSortChoice> =
        combine(folderSorts, _connection, _path, currentListingHasSubfolders) { sorts, connection, path, hasSubfolders ->
            connection?.let { sorts.choiceAt(it.id, path, hasSubfolders) }
                ?: FolderSorts.NONE.choiceAt(0, path, hasSubfolders)
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, FolderSorts.NONE.choiceAt(0, ""))

    val sortState: StateFlow<SortType> =
        folderSortState.map { it.current }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SortType.NAME_ASC)

    fun sort(sortType: SortType) {
        val folder = currentFolder() ?: return
        viewModelScope.launch(Dispatchers.IO) { folderSortRepository.choose(folder, sortType) }
    }

    fun inheritSort() {
        val folder = currentFolder() ?: return
        viewModelScope.launch(Dispatchers.IO) { folderSortRepository.clear(folder) }
    }

    val groupModeState: StateFlow<GroupMode> =
        combine(folderSorts, _connection, _path, groupModes.changes) { sorts, connection, path, _ ->
            if (connection == null || path != newestAdditionsPath) GroupMode.UNGROUPED
            else groupModes.modeAt(sorts.identityOf(connection.id, path))
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, GroupMode.UNGROUPED)

    fun group(mode: GroupMode) {
        val folder = currentFolder() ?: return
        groupModes.choose(folder, mode)
    }

    private fun currentFolder(): FileIdentity? {
        val connectionId = _connection.value?.id ?: return null
        return folderSorts.value.identityOf(connectionId, _path.value)
    }

    // The collector that watches path/search/refresh. Held so a second call to listFiles
    // replaces it rather than adding another: reconnecting while one is already running
    // would otherwise leave both alive, each reacting to every later path change.
    //
    // A refresh no longer restarts it at all — that is what used to make a single
    // pull-to-refresh leak into every directory opened afterwards. It goes through
    // _refreshRequest instead.
    private var listFilesCollector: Job? = null

    private data class ListingRequest(
        val path: String,
        val refresh: RefreshRequest?,
        val showHidden: Boolean,
    )

    private val showHiddenFilesState: StateFlow<Boolean> =
        settingsRepository.showHiddenFiles().stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
            combine(pathState, _refreshRequest, showHiddenFilesState) { path, request, showHidden ->
                ListingRequest(path, request, showHidden)
            }.collectLatest { (path, request, showHidden) ->
                // collectLatest cancels the previous body when a new value arrives, which is
                // what the hand-rolled job juggling here was approximating.
                //
                // Anything thrown in here reaches viewModelScope and takes the process with
                // it, and SMB throws for entirely ordinary reasons — the NAS asleep, a folder
                // renamed on the server, credentials rotated. Listing a directory that no
                // longer exists was a hard crash (STATUS_OBJECT_NAME_NOT_FOUND), so surface
                // it the way connect() already surfaces a failed connection.
                try {
                    if (path == newestAdditionsPath) {
                        _isRefreshing.update { false }
                        _tagsRefreshed.collect { listNewestAdditions(connectionId) }
                        return@collectLatest
                    }

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

                    val refresh = if (request?.path == path) {
                        RefreshMode.FORCE
                    } else {
                        RefreshMode.IF_STALE
                    }

                    val folderAttributes = mapOf(
                        "source" to sourceKind(connectionId),
                        "depth" to depthOf(path),
                        "refresh" to refresh.name.lowercase(),
                    )
                    metrics.folderOpened(folderAttributes)
                    val openedAt = System.currentTimeMillis()
                    var painted = false

                    metrics.traced("folder.listing", refresh.name.lowercase()) {
                        fileRepository.listFiles(connectionId, path, refresh, showHidden)
                            .collect { listing ->
                                _list.update {
                                    it.plus(path to listing.files.withThumbnailEligibility())
                                }
                                _isRefreshing.update { listing.syncing }

                                val elapsed = System.currentTimeMillis() - openedAt
                                if (!painted) {
                                    painted = true
                                    metrics.record(
                                        LoadMetric.FOLDER_FIRST_PAINT,
                                        elapsed,
                                        folderAttributes + ("syncing" to listing.syncing.toString()),
                                    )
                                }

                                fetchThumbnails(connectionId, listing.files)
                                if (!listing.syncing) {
                                    metrics.record(LoadMetric.FOLDER_SETTLED, elapsed, folderAttributes)
                                    scanTagsJob(connectionId, refresh == RefreshMode.FORCE)
                                }
                            }
                    }

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

    private suspend fun listNewestAdditions(connectionId: Int) {
        val files = pairingRepository.current().inFrameOf(connectionId, fileRepository.getNewestAdditions())
        _list.update { it.plus(newestAdditionsPath to files.withThumbnailEligibility()) }
        fetchThumbnails(connectionId, files)
    }

    fun openNewestAdditions() {
        if (_path.value == newestAdditionsPath) return
        clearSearch()
        changePath(newestAdditionsPath)
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

    fun getSortFunction(
        sortType: SortType,
        views: Map<String, ViewTotals> = emptyMap(),
        created: Map<String, Date> = emptyMap(),
    ): Comparator<LocalFile> = fileComparator(sortType, views, created)

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

    val showCaptionsState: StateFlow<Boolean> =
        settingsRepository.showCaptions().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowCaptions(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowCaptions(show) }
    }

    val captionedFoldersState: StateFlow<Set<String>> =
        combine(_list, _path, _captioned, showCaptionsState, pairingsState) { list, path, captioned, show, _ ->
            if (!show || captioned.isEmpty()) return@combine emptySet()
            val pairings = pairingRepository.current()
            list[path].orEmpty()
                .filter { it.type == FileType.FOLDER }
                .map { it.fullPath() to pairings.canonicalize(it.connectionId, it.fullPath()) }
                .filter { (_, canonical) -> AnnotatedDirectory(canonical.connectionId, canonical.path) in captioned }
                .map { (path, _) -> path }
                .toSet()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    suspend fun captionFor(image: LocalFile): Caption? {
        val gallery = pairingRepository.current().canonicalize(image.connectionId, image.parentPath)
        return try {
            captionRepository.captionFor(gallery.connectionId, gallery.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ExplorerViewModel", "Could not read the caption for ${image.parentPath}", e)
            null
        }
    }

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
        clearConnectionTest()
        viewModelScope.launch { _connection.update { connection } }
    }

    fun deleteConnection(connection: Connection) {
        viewModelScope.launch {
            connectionRepository.deleteConnection(connection)
            _connections.update { it.minus(connection) }
            if (locationConnectionId == connection.id) locationConnectionId = null
            lastLocation.forget(connection.id)
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
    private val _tagsRefreshed = MutableStateFlow(0)
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

    val tagDisplayLabelsState: StateFlow<Map<String, String>> =
        _tags.map(::displayLabels)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val visibleTagsState: StateFlow<List<LocalTagWithMetadata>> =
        combine(_tags, _tagSearch, _tagQuery, _tagSort, tagViews) { tags, search, query, sort, views ->
            val matching =
                if (query.isBlank()) tags else tags.filter { search.matches(it.tag, query, it.label) }
            matching.sortedWith(tagComparator(sort, views, displayLabels(tags)))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun searchTags(query: String) {
        _tagQuery.update { query }
    }

    /**
     * What the header names an open tag: its group's label, shown as the list shows it
     * (`hagoromo gitsune`, not `character:hagoromo gitsune`). Null when the path is not a tag.
     */
    val openTagLabelState: StateFlow<String?> =
        combine(_path, tagDisplayLabelsState) { path, shown ->
            if (!path.startsWith(tagPrefix)) return@combine null
            val tag = path.substringAfter(tagPrefix)
            shown[tag] ?: tag
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val listedTagChips: StateFlow<Map<String, TagChip>> =
        _tags.map(::listedChips)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val folderTagsState: StateFlow<List<TagChip>> =
        combine(_connection, _path, pairingsState, listedTagChips, _tagsRefreshed) { connection, path, _, listed, _ ->  // stack-reviewer-ignore: pairingsState and _tagsRefreshed are triggers only; combine re-emits on any source change and nothing downstream dedupes before the map, which reads pairingRepository.current() fresh, so a pairing change re-runs the lookup
            Triple(connection, path, listed)  // stack-reviewer-ignore: combine emits on every source change without comparing values and nothing between it and the map is distinctUntilChanged, so a bumped _tagsRefreshed re-runs the query even when this Triple is equal to the last one
        }
            .map { (connection, path, listed) ->
                if (connection == null || isPseudoFolder(path)) {
                    emptyList()
                } else {
                    tagChipsOn(pairingRepository.current().canonicalize(connection.id, path), null, listed)
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun tagsFor(image: LocalFile): List<TagChip> {
        val gallery = pairingRepository.current().canonicalize(image.connectionId, image.parentPath)
        return tagChipsOn(gallery, image.filename, listedTagChips.value)
    }

    private suspend fun tagChipsOn(
        place: FileIdentity,
        filename: String?,
        listed: Map<String, TagChip>,
    ): List<TagChip> = try {
        tagChips(tagRepository.tagsOn(place, filename), listed)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("ExplorerViewModel", "Could not read the tags on ${place.path}", e)
        emptyList()
    }

    fun getFilesWithTag(tag: LocalTag) {
        clearSearch()
        changePath("${tagPrefix}${tag.tag}")
    }

    val columnsState: StateFlow<GridColumns> =
        settingsRepository.columns()
            .stateIn(viewModelScope, SharingStarted.Eagerly, GridColumns.AUTO)

    fun setColumns(columns: GridColumns) {
        viewModelScope.launch { settingsRepository.setColumns(columns) }
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

    private val coversSettled = ConcurrentHashMap<String, CoverOrder>()

    private val visitSeed = _path.map { Random.nextLong() }

    private val _coverChoicesChanged = MutableStateFlow(0)

    private val _appliedCovers = MutableStateFlow<Map<String, CoverMode>>(emptyMap())
    val appliedCoversState = _appliedCovers.asStateFlow()

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
        _appliedCovers.update { emptyMap() }  // stack-reviewer-ignore: a choice change bumps _coverChoicesChanged, which re-runs the combine and overwrites the entry for every wanted folder before its cover loads; an off-screen folder is recomputed as soon as it is wanted again, and an entry for a folder no longer listed is never looked up
        coverFailedAt.clear()
        coverLoader?.cancel()
        coverLoader = viewModelScope.launch(Dispatchers.IO) {
            combine(
                _wantedCovers,
                sortState.map(::coverOrderFor).distinctUntilChanged(),
                visitSeed,
                _coverChoicesChanged,
                combine(settingsRepository.coverShuffle(), settingsRepository.firstPageFolders(), ::Pair),  // stack-reviewer-ignore: only a recompute trigger; CoverPreferences reads both settings itself through shuffleSetting and firstPageFolders
            ) { wanted, sorted, seed, _, _ -> Triple(wanted, sorted, seed) }
                .collectLatest { (wanted, sorted, seed) ->
                    coroutineScope {
                        val now = System.currentTimeMillis()
                        wanted
                            .filter { path ->
                                coverFailedAt[path]?.let { now - it >= COVER_RETRY_MILLIS } ?: true
                            }
                            .forEach { path ->
                                launch {
                                    val chosen = coverPreferences.coverFor(connectionId, path, sorted, seed)
                                    _appliedCovers.update { it + (path to chosen.applied) }  // stack-reviewer-ignore: path is what the grid passed to requestCovers, item.fullPath() in ExplorerScreen, the same string the screen looks the badge up by; _thumbnails is keyed the same way
                                    if (coversSettled[path] != chosen.order) loadCover(connectionId, path, chosen.order)
                                }
                            }
                    }
                }
        }
    }

    suspend fun coverChoiceFor(path: String): CoverMode {
        val connectionId = browsingConnectionId ?: return CoverMode.DEFAULT
        return coverPreferences.choiceFor(connectionId, path)
    }

    suspend fun folderKeepsFirstPage(path: String): Boolean {
        val connectionId = browsingConnectionId ?: return false
        return coverPreferences.keepsFirstPage(connectionId, path)
    }

    fun chooseCover(path: String, mode: CoverMode) {
        val connectionId = browsingConnectionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            coverPreferences.choose(connectionId, path, mode)
            _coverChoicesChanged.update { it + 1 }
        }
    }

    fun coverImageMissing(imagePath: String, url: String) {
        val connectionId = browsingConnectionId ?: return
        if (runCatching { getUrl(imagePath, true) }.getOrNull() != url) return  // stack-reviewer-ignore: dropping a report whose URL the current state would not build is the point. A tile still mounted from the previous connection is exactly the report to drop, and any other dropped report falls back to today's behaviour, the placeholder until the cover is next recomputed, never to a wrong skip.
        val folders = _thumbnails.value
            .filter { (folder, images) -> folder != imagePath && imagePath in images }
            .keys
        if (folders.isEmpty()) return
        _thumbnails.update { thumbnails ->
            thumbnails + folders.associateWith { thumbnails[it].orEmpty() - imagePath }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                fileRepository.forgetMissingCoverImage(connectionId, imagePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ExplorerViewModel", "Could not re-list the folder of missing '$imagePath'", e)
            }
            folders.forEach(coversSettled::remove)
            _coverChoicesChanged.update { it + 1 }
        }
    }

    private suspend fun loadCover(connectionId: Int, path: String, order: CoverOrder) {
        try {
            val startedAt = System.currentTimeMillis()
            val cover = fileRepository.folderCover(connectionId, path, order)
            val elapsed = System.currentTimeMillis() - startedAt
            if (cover.complete) coversSettled[path] = order
            Log.d(
                "ExplorerViewModel",
                "Cover for $path: ${cover.images.size} in ${elapsed}ms " +
                    "(order=${order.label}, borrowed=${cover.borrowed}, " +
                    "listings=${cover.listings}, complete=${cover.complete})"
            )
            metrics.record(
                LoadMetric.FOLDER_COVER,
                elapsed,
                mapOf(
                    "source" to sourceKind(connectionId),
                    "empty" to cover.images.isEmpty().toString(),
                    "borrowed" to cover.borrowed.toString(),
                    "listings" to listingsBucket(cover.listings),
                    "complete" to cover.complete.toString(),
                    "order" to order.label,
                ),
            )
            if (cover.images.isNotEmpty()) _thumbnails.update { it.plus(path to cover.images) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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

    val listingViewsState: StateFlow<Map<String, ViewTotals>> =
        combine(_list, _path, sortState, viewRecorder.changes) { list, path, sort, _ ->
            Triple(list[path].orEmpty(), sort.usesViews || path == newestAdditionsPath, browsingConnectionId)
        }
            .map { (files, needsViews, connectionId) ->
                if (!needsViews || connectionId == null || files.isEmpty()) {
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

    val listingCreatedState: StateFlow<ListingCreated> =
        combine(_list, _path, sortState) { list, path, sort ->
            list[path].orEmpty().takeIf { needsCreatedDates(sort, path) }
        }
            .map { files ->
                if (files.isNullOrEmpty()) {
                    ListingCreated.NONE
                } else try {
                    ListingCreated(files, fileRepository.createdDates(files, pairingRepository.current()))
                } catch (e: Exception) {
                    Log.w("ExplorerViewModel", "Could not read created dates", e)
                    ListingCreated(files, emptyMap())
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListingCreated.NONE)

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