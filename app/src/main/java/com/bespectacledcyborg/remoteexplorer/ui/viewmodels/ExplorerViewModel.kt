package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import android.app.Application
import android.util.Log
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
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.AllFilesAccessMissing
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.tagPrefix
import com.bespectacledcyborg.remoteexplorer.data.tasks.ScanTagsWorker
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.bespectacledcyborg.remoteexplorer.utils.Utils.constructUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortType {
    NAME_ASC,
    NAME_DESC,
    LAST_MODIFIED_ASC,
    LAST_MODIFIED_DESC
}

enum class ViewType {
    GRID,
    LIST
}

typealias ThumbnailsMap = Map<String, List<String>>

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
) : AndroidViewModel(application) {
    private val _path = MutableStateFlow("")
    val pathState: StateFlow<String> = _path.asStateFlow()
    private val _navigationStack = MutableStateFlow(listOf(""))

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

    // On init: start the http server, find connections, apply the saved column count, and
    // start following the tag scan.
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
            val defaultColumnCount = settingsRepository.getSettingsOnce()?.defaultColumnCount
            if (defaultColumnCount != null && !columnCountChosenThisSession) {
                _columnCount.update { defaultColumnCount }
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
        _tags.update { tags }
        _tagThumbnails.update { fetchTagThumbnails(tags) }
    }

    // 1. connect to source
    // 2. start watching `path` and then update `files` based on the value
    fun connect(connection: Connection) {
        viewModelScope.launch {
            try {
                _connection.update { connection }
                _list.update { emptyMap() }
                _thumbnails.update { emptyMap() }
                _tagIndexIsPartial.update {
                    !tagRepository.capabilitiesFor(connection.id).indexesUnvisitedDirectories
                }

                fileRepository.connect(connection)

                // Before the first listing builds a URL: Sketch caches by URL, and one built
                // from pairings not yet read would name a paired file as the local copy
                // rather than the remote — a second cache entry for the same picture.
                pairingRepository.awaitLoaded()
                listFiles(connection.id)
            } catch (e: AllFilesAccessMissing) {
                _toast.emit("Allow all files access to open ${connection.name}")
            } catch (e: Exception) {
                _toast.emit(
                    when (connection.type) {
                        ConnectionType.SMB -> "Unable to connect to ${connection.hostname}"
                        // No host to name, and the reason — a folder gone with its card, say —
                        // is the useful part.
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

    private fun listFiles(connectionId: Int) {
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
                        val files = fileRepository.getFilesWithTag(LocalTag(tag))
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

    fun getSortFunction(sortType: SortType): Comparator<LocalFile> {
        return when (sortType) {
            SortType.NAME_ASC -> compareBy { it.filename }
            SortType.NAME_DESC -> compareByDescending { it.filename }
            SortType.LAST_MODIFIED_ASC -> compareBy { it.lastModified }
            SortType.LAST_MODIFIED_DESC -> compareByDescending { it.lastModified }
        }
    }

    fun addConnection(connection: Connection) {
        viewModelScope.launch {
            connectionRepository.addConnection(connection)
            _connections.update { it.plus(connection) }
            _connection.update { connection }
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

    private val _columnCount = MutableStateFlow(SettingsEntity().defaultColumnCount)
    val columnCountState: StateFlow<Int> = _columnCount.asStateFlow()

    // The settings screen has always offered "Default Column Count" and nothing ever read it;
    // the grid was hardcoded to 4. Applied once at startup rather than by observing the flow,
    // so that picking a column count from the toolbar during a session is not clobbered the
    // next time settings emit.
    private var columnCountChosenThisSession = false

    fun setColumnCount(columnCount: Int) {
        columnCountChosenThisSession = true
        _columnCount.update { columnCount }
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

    private fun fetchThumbnails(connectionId: Int, files: List<LocalFile>) {
        viewModelScope.launch {
            val thumbnailsMap =
                files.associate {
                    it.fullPath() to when {
                        it.type == FileType.FOLDER -> fileRepository.getFolderThumbnails(
                            connectionId,
                            it.fullPath()
                        )

                        else -> listOf(it.fullPath())
                    }
                }
            _thumbnails.update {
                it.plus(thumbnailsMap)
            }
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

    private suspend fun fetchTagThumbnails(
        tags: List<LocalTagWithMetadata>
    ): Map<String, List<String>> {
        val thumbnails =
            tags.associate {
                val tagIdentifier = "${tagPrefix}${it.tag}"
                if (it.count > 0) {
                    tagIdentifier to fileRepository.getTagThumbnails(LocalTag(it.tag), 4)
                } else {
                    tagIdentifier to emptyList()
                }
            }
        return thumbnails
    }
}