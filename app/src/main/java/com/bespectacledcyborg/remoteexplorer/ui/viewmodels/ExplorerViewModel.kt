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
import com.bespectacledcyborg.remoteexplorer.data.ServerRepository
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import com.bespectacledcyborg.remoteexplorer.data.TagRepository
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
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
    private val thumbnailManager: ThumbnailManager = ThumbnailManager(application)
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

                fileRepository.connect(
                    connection.id,
                    connection.hostname,
                    connection.port,
                    connection.username,
                    connection.password.value,
                    connection.domain,
                    connection.share,
                )

                listFiles(connection.id, false)
            } catch (e: Exception) {
                _toast.emit("Unable to connect to ${connection.hostname}")
            }
        }

        viewModelScope.launch {
            _connectionName.update { connection.name + " » " + connection.share }
        }
    }

    fun testConnection(connection: Connection) {
        viewModelScope.launch {
            try {
                fileRepository.connect(
                    connection.id,
                    connection.hostname,
                    connection.port,
                    connection.username,
                    connection.password.value,
                    connection.domain,
                    connection.share,
                )
                _toast.emit("Connected to ${connection.hostname}")
            } catch (e: Exception) {
                _toast.emit(e.message ?: "Unable to connect to ${connection.hostname}")
            }
        }
    }

    fun changePath(path: String) {
        _path.update { path }
        _navigationStack.update { it.plus(path) }
    }

    fun goBack() {
        _navigationStack.update { it.dropLast(1) }
        _path.update { _navigationStack.value.lastOrNull().orEmpty() }
    }

    fun getUrl(path: String, thumbnail: Boolean = false): String {
        val connectionId =
            _connection.value?.id
                ?: throw IllegalStateException(
                    "Constructing a URL when connectionId is null"
                )
        return constructUrl(connectionId, path, thumbnail)
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

    // The collector that watches path/search. Held so a second call to listFiles replaces it
    // rather than adding another: connect() and forceRefresh() both call in, so without this
    // every refresh left an extra collector alive, each still reacting to later path changes
    // and re-listing with its own stale forceRemote.
    private var listFilesCollector: Job? = null

    private fun listFiles(connectionId: Int, forceRemote: Boolean) {
        listFilesCollector?.cancel()
        listFilesCollector = viewModelScope.launch(Dispatchers.IO) {
            combine(pathState, searchState) { path, search ->
                Pair(
                    path,
                    search
                )
            }.collectLatest { (path, search) ->
                // collectLatest cancels the previous body when a new value arrives, which is
                // what the hand-rolled job juggling here was approximating.
                //
                // Anything thrown in here reaches viewModelScope and takes the process with
                // it, and SMB throws for entirely ordinary reasons — the NAS asleep, a folder
                // renamed on the server, credentials rotated. Listing a directory that no
                // longer exists was a hard crash (STATUS_OBJECT_NAME_NOT_FOUND), so surface
                // it the way connect() already surfaces a failed connection.
                try {
                    val files = if (path.startsWith(tagPrefix)) {
                        val tag = path.substringAfter(tagPrefix)
                        fileRepository.getFilesWithTag(LocalTag(tag))
                    } else {
                        fileRepository.listFiles(
                            connectionId,
                            path,
                            search,
                            forceRemote
                        )
                    }.map {
                        it.copy(
                            canHaveThumbnail =
                                Utils.canHaveThumbnail(it.filename)
                        )
                    }
                    _list.update { it.plus(path to files) }

                    fetchThumbnails(connectionId, files)
//                    thumbnailsJob(connectionId, files)
                    scanTagsJob(connectionId, forceRemote)
                } catch (e: CancellationException) {
                    throw e // collectLatest cancels this body on every new value; not an error.
                } catch (e: Exception) {
                    Log.e("ExplorerViewModel", "Failed to list '$path'", e)
                    _toast.emit(
                        "Could not open ${path.ifEmpty { "the share" }}: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun forceRefresh() {
        val connection = connectionState.value
        Log.d("ForceRefresh", "Refreshing for connection: $connection")
        if (connection != null) {
            listFiles(connection.id, true)
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

    fun getFilesWithTag(tag: LocalTag) {
        viewModelScope.launch { _path.update { "${tagPrefix}${tag.tag}" } }
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

    fun openFileRange(
        connectionId: Int,
        fullPath: String,
        buffer: ByteArray,
        start: Int,
        end: Int
    ): Int {
        return fileRepository.openFileRange(connectionId, fullPath, buffer, start, end)
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