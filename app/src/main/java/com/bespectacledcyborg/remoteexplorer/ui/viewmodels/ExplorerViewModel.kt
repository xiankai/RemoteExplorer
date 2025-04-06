package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import com.bespectacledcyborg.remoteexplorer.data.ConnectionRepository
import com.bespectacledcyborg.remoteexplorer.data.FileRepository
import com.bespectacledcyborg.remoteexplorer.data.ServerRepository
import com.bespectacledcyborg.remoteexplorer.data.TagRepository
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.tagPrefix
import com.bespectacledcyborg.remoteexplorer.data.tasks.ScanTagsWorker
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_GENERATION_WORK_TAG
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_WORKER_OUTPUT_FULL_PATH
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_WORKER_OUTPUT_PARENT_PATH
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.bespectacledcyborg.remoteexplorer.utils.Utils.constructUrl
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

class ExplorerViewModel(
    application: Application,
    private val fileRepository: FileRepository,
    private val serverRepository: ServerRepository,
    private val connectionRepository: ConnectionRepository,
    private val tagRepository: TagRepository,
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

    // On init:
    // start http server
    // find connections
    // populate tags
    init {
        viewModelScope.launch { serverRepository.startup() }
        viewModelScope.launch {
            connectionRepository.getConnections().collect { connections ->
                _connections.update { connections }
            }
        }
        viewModelScope.launch {
            val tags = tagRepository.getTags()
            _tags.update { tags }
            launch {
                val tagThumbnails = fetchTagThumbnails(tags)
                _tagThumbnails.update { tagThumbnails }
            }
        }
    }

    // 1. connect to source
    // 2. start watching `path` and then update `files` based on the value
    fun connect(connection: Connection) {
        viewModelScope.launch {
            try {
                _connection.update { connection }

                fileRepository.connect(
                    connection.id,
                    connection.hostname,
                    connection.username,
                    connection.password,
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
                    connection.username,
                    connection.password,
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

    // Job to track the running coroutine
    private var listFileJob: Job? = null

    private fun listFiles(connectionId: Int, forceRemote: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            combine(pathState, searchState) { path, search ->
                Pair(
                    path,
                    search
                )
            }.collect { (path, search) ->
                // Cancel the previous coroutine if it's still running
                listFileJob?.cancel()

                // Launch a new coroutine for the new path
                listFileJob = launch {
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

//                    _thumbnails.update {
//                        fetchThumbnails(connectionId, files, forceRemote)
//                    }
                    thumbnailsJob(connectionId, files)
                    scanTagsJob(connectionId, forceRemote)
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

    private val _columnCount = MutableStateFlow(4)
    val columnCountState: StateFlow<Int> = _columnCount.asStateFlow()

    fun setColumnCount(columnCount: Int) {
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

    private val workManager = WorkManager.getInstance(application.applicationContext)
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

        workManager.enqueueUniqueWork("scanTags", ExistingWorkPolicy.KEEP, workRequest)
    }

    private fun thumbnailsJob(connectionId: Int, files: List<LocalFile>) {
        viewModelScope.launch {
            // Load already usable thumbnails
            val ready = files.filter { it.thumbnailStatus == ThumbnailStatus.SUCCESS }
                .associate { it.filename to listOf(it.filename) }
            val folders = files.filter { it.thumbnailStatus == ThumbnailStatus.NESTED }
                .associate {
                    it.filename to fileRepository.getFolderThumbnails(
                        connectionId,
                        it.filename
                    )
                }
            _thumbnails.update {
                it.plus(ready).plus(folders)
            }

            // Process pending thumbnails in the background
            val toProcess = files.filter { it.thumbnailStatus == ThumbnailStatus.UNPROCESSED }
                .associate { it.filename to listOf(it.filename) }

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