package com.example.remoteexplorer.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.remoteexplorer.data.ConnectionRepository
import com.example.remoteexplorer.data.FileRepository
import com.example.remoteexplorer.data.ServerRepository
import com.example.remoteexplorer.data.TagRepository
import com.example.remoteexplorer.data.db.Connection
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.data.db.LocalFile
import com.example.remoteexplorer.data.db.LocalTag
import com.example.remoteexplorer.data.db.LocalTagWithMetadata
import com.example.remoteexplorer.data.tagPrefix
import com.example.remoteexplorer.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Paths

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

class ExplorerViewModel(
    application: Application,
    private val fileRepository: FileRepository,
    private val serverRepository: ServerRepository,
    private val connectionRepository: ConnectionRepository,
    private val tagRepository: TagRepository,
): AndroidViewModel(application) {
    private val _path = MutableStateFlow("")
    val pathState: StateFlow<String> = _path.asStateFlow()
    private val _navigationStack = MutableStateFlow(listOf(""))

    private val _list: MutableStateFlow<List<LocalFile>> = MutableStateFlow(emptyList())
    val listState: StateFlow<List<LocalFile>> = _list.asStateFlow()

    private val _connection: MutableStateFlow<Connection?> = MutableStateFlow(null)
    val connectionState: StateFlow<Connection?> = _connection.asStateFlow()
    private val _connections: MutableStateFlow<List<Connection>> = MutableStateFlow(emptyList())
    val connectionsState: StateFlow<List<Connection>> = _connections.asStateFlow()
    private val _connectionName = MutableStateFlow("")
    val connectionNameState = _connectionName.asStateFlow()

    private val _toast = MutableSharedFlow<String>()
    val toast = _toast.asSharedFlow()

    private val _thumbnails: MutableStateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap())
    val thumbnailsState = _thumbnails.asStateFlow()

    // On init:
    // start http server
    // find connections
    init {
        viewModelScope.launch {
            serverRepository.startup()
        }
        viewModelScope.launch {
            connectionRepository.getConnections().collect { connections ->
                _connections.update {
                    connections
                }
            }
        }
        viewModelScope.launch {
            val tags = tagRepository.getTags()
            _tags.update {
                tags
            }
            launch {
                Log.d("ExplorerViewModel#getTags", "Starting to fetch thumbnails")
                val thumbnails = tags.associate {
                    val tagIdentifier = "${tagPrefix}${it.tag}"
                    if (it.count > 0) {
                        tagIdentifier to fileRepository.getTagThumbnails(LocalTag(it.tag), 4)
                    } else {
                        tagIdentifier to emptyList()
                    }
                }
                Log.d("ExplorerViewModel#getTags", "Thumbnails found: $thumbnails")
                _thumbnails.update { thumbnails }
            }
        }
    }

    // 1. connect to source
    // 2. start watching `path` and then update `files` based on the value
    fun connect(connection: Connection) {
        viewModelScope.launch {
            try {
                _connection.update {
                    connection
                }

                fileRepository.connect(
                    connection.hostname,
                    connection.username,
                    connection.password,
                    connection.domain,
                    connection.share,
                )

                listFiles(connection.id, false)
            } catch (e: Exception) {
                _toast.emit(e.message ?: "Unable to connect to ${connection.hostname}")
            }
        }

        viewModelScope.launch {
            _connectionName.update {
                connection.name + " » " + connection.share
            }
        }
    }

    fun testConnection(connection: Connection) {
        viewModelScope.launch {
            try {
                fileRepository.connect(
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
        _path.update {
            path
        }
        _navigationStack.update {
            it.plus(path)
        }
    }

    fun goBack() {
        _navigationStack.update {
            it.dropLast(1)
        }
        _path.update {
            _navigationStack.value.lastOrNull().orEmpty()
        }
    }

    fun getUrl(path: String): String {
        return serverRepository.constructUrl(path)
    }

    private val _search: MutableStateFlow<String?> = MutableStateFlow(null)
    val searchState: StateFlow<String?> = _search.asStateFlow()
    fun search(query: String) {
        _search.update {
            query
        }
    }

    private val _filter = MutableStateFlow<List<String>>(emptyList())
    val filterState: StateFlow<List<String>> = _filter.asStateFlow()
    fun filter(extensions: List<String>) {
        _filter.update {
            extensions
        }
    }

    private val _sort = MutableStateFlow(SortType.NAME_ASC)
    val sortState: StateFlow<SortType> = _sort.asStateFlow()
    fun sort(sortType: SortType) {
        _sort.update {
            sortType
        }
        _list.update {
            it.sortedWith(getSortFunction(sortType))
        }
    }

    // Job to track the running coroutine
    private var listFileJob: Job? = null
    private var thumbnailJob: Job? = null
    private var tagJob: Job? = null

    private fun listFiles(connectionId: Int, forceRemote: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            pathState.collect { path ->
                // Cancel the previous coroutine if it's still running
                listFileJob?.cancel()
                thumbnailJob?.cancel()
                tagJob?.cancel()

                // Launch a new coroutine for the new path
                listFileJob = launch {
                    val files = if (path.startsWith(tagPrefix)) {
                        val tag = path.substringAfter(tagPrefix)
                        fileRepository.getFilesWithTag(LocalTag(tag))
                    } else {
                        fileRepository.listFiles(
                            connectionId,
                            path,
                            searchState.value,
                            forceRemote
                        )
                    }.map {
                        it.copy(canHaveThumbnail = Utils.canHaveThumbnail(it.filename))
                    }
                    _list.update {
                        files.sortedWith(getSortFunction(sortState.value))
                    }

                    thumbnailJob = launch {
                        Log.d("ExplorerViewModel#ListFiles", "Starting to fetch thumbnails")
                        val thumbnails = files.associate {
                            val fullPath = Paths.get(it.parentPath, it.filename).toString()
                            if (Utils.canHaveThumbnail(it.filename)) {
                                it.filename to listOf(fullPath)
                            } else if (it.type === FileType.FOLDER) {
                                it.filename to fileRepository.getFolderThumbnails(connectionId, fullPath, 4, forceRemote)
                            } else {
                                it.filename to emptyList()
                            }
                        }
                        Log.d("ExplorerViewModel#ListFiles", "Thumbnails found: $thumbnails")
                        _thumbnails.update { thumbnails }
                    }

                    tagJob = launch {
                        tagRepository.scanTags(connectionId, forceRemote)
                    }
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

    private fun getSortFunction(sortType: SortType): Comparator<LocalFile> {
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
            _connections.update {
                it.plus(connection)
            }
            _connection.update {
                connection
            }
        }
    }

    fun editConnection(connection: Connection?) {
        viewModelScope.launch {
            _connection.update {
                connection
            }
        }
    }

    fun deleteConnection(connection: Connection) {
        viewModelScope.launch {
            connectionRepository.deleteConnection(connection)
            _connections.update {
                it.minus(connection)
            }
        }
    }

    private val _browsableImages = MutableStateFlow(emptyList<LocalFile>())
    val browsableImagesState = _browsableImages.asStateFlow()
    private val _initialBrowsableImage = MutableStateFlow(0)
    val initialBrowsableImageState = _initialBrowsableImage.asStateFlow()

    fun browseImages(browsableImages: List<LocalFile>, initialBrowsableImage: Int) {
        viewModelScope.launch {
            _browsableImages.update {
                browsableImages
            }
            _initialBrowsableImage.update {
                initialBrowsableImage
            }
        }
    }

    private val _tags = MutableStateFlow(emptyList<LocalTagWithMetadata>())
    val tagsState = _tags.asStateFlow()

    fun getFilesWithTag(tag: LocalTag) {
        viewModelScope.launch {
            _path.update {
                "${tagPrefix}${tag.tag}"
            }
        }
    }

    private val _columnCount = MutableStateFlow(4)
    val columnCountState: StateFlow<Int> = _columnCount.asStateFlow()

    fun setColumnCount(columnCount: Int) {
        _columnCount.update {
            columnCount
        }
    }

    fun openFileRange(fullPath: String, buffer: ByteArray, start: Int, end: Int): Int {
        return fileRepository.openFileRange(fullPath, buffer, start, end)
    }

    fun getFileSize(fullPath: String): Long {
        return fileRepository.getFileSize(fullPath)
    }

    private val _tagScreenViewType = MutableStateFlow(ViewType.GRID)
    val tagScreenViewTypeState = _tagScreenViewType.asStateFlow()

    fun setTagScreenViewType(viewType: ViewType) {
        _tagScreenViewType.update {
            viewType
        }
    }
}