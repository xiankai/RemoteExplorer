package com.example.remoteexplorer.ui.viewmodels

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.remoteexplorer.data.ConnectionRepository
import com.example.remoteexplorer.data.File
import com.example.remoteexplorer.data.FileRepository
import com.example.remoteexplorer.data.ServerRepository
import com.example.remoteexplorer.data.ThumbnailRepository
import com.example.remoteexplorer.data.db.Connection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortType {
    NAME_ASC,
    NAME_DESC,
    LAST_MODIFIED_ASC,
    LAST_MODIFIED_DESC
}

class ExplorerViewModel(
    application: Application,
    private val fileRepository: FileRepository,
    private val serverRepository: ServerRepository,
    private val thumbnailRepository: ThumbnailRepository,
    private val connectionRepository: ConnectionRepository,
    private val defaultDispatcher: CoroutineDispatcher,
): AndroidViewModel(application) {
//    private lateinit var fileRepository: FileRepository
//    private lateinit var serverRepository: ServerRepository
//    private lateinit var thumbnailRepository: ThumbnailRepository
//    private lateinit var connectionRepository: ConnectionRepository

//    private var smbFileDataSource: SambaFileDataSource = SambaFileDataSource()
//    private var localDatabaseSource: LocalDatabaseDataSource = LocalDatabaseDataSource()
//    private var connectionDataSource: ConnectionDataSource = ConnectionDataSource(ConnectionDao)

    private val _path = MutableStateFlow("")
    val pathState: StateFlow<String> = _path.asStateFlow()
    private val _navigationStack = MutableStateFlow(listOf(""))
    private val _screen = MutableStateFlow("connection")
    val screenState: StateFlow<String> = _screen.asStateFlow()

    private val _list: MutableStateFlow<List<File>> = MutableStateFlow(emptyList())
    val listState: StateFlow<List<File>> = _list.asStateFlow()

    private val _search: MutableStateFlow<String?> = MutableStateFlow(null)
    val searchState: StateFlow<String?> = _search.asStateFlow()
    private val _sort = MutableStateFlow(SortType.NAME_ASC)
    val sortState: StateFlow<SortType> = _sort.asStateFlow()

    private val _connection: MutableStateFlow<Connection?> = MutableStateFlow(null)
    val connectionState: StateFlow<Connection?> = _connection.asStateFlow()
    private val _connections: MutableStateFlow<List<Connection>> = MutableStateFlow(emptyList())
    val connectionsState: StateFlow<List<Connection>> = _connections.asStateFlow()

    private val _toast = MutableSharedFlow<String>()
    val toast = _toast.asSharedFlow()


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
    }

    // 1. connect to source
    // 2. start watching `path` and then update `files` based on the value
    fun connect(connection: Connection) {
        viewModelScope.launch {
            try {
                fileRepository.connect(
                    connection.hostname,
                    connection.username,
                    connection.password,
                    connection.domain,
                    connection.share,
                )

                pathState.collect { newPath ->
                    val files = fileRepository.listFiles(newPath, searchState.value)
                    _list.update {
                        files.sortedWith(getSortFunction(sortState.value))
                    }
                    _screen.update {
                        "list"
                    }
                }
            } catch (e: Exception) {
                _toast.emit(e.message ?: "Unable to connect to ${connection.hostname}")
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

    fun changeScreen(screen: String) {
        _screen.update {
            screen
        }
        _navigationStack.update {
            emptyList()
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
        _path.update {
            _navigationStack.value.lastOrNull().orEmpty()
        }
        _navigationStack.update {
            it.dropLast(1)
        }
    }

    fun getUrl(path: String): String {
        return serverRepository.constructUrl(path)
    }

    fun generateThumbnail(path: String) {
        viewModelScope.launch {
            thumbnailRepository.generateImageThumbnail(path)
        }
    }

    fun search(query: String) {
        _search.update {
            query
        }
    }

    fun sort(sortType: SortType) {
        _sort.update {
            sortType
        }
        _list.update {
            listState.value.sortedWith(getSortFunction(sortType))
        }
    }

    private fun getSortFunction(sortType: SortType): Comparator<File> {
        return when (sortType) {
            SortType.NAME_ASC -> compareBy { it.filename }
            SortType.NAME_DESC -> compareByDescending { it.filename }
            SortType.LAST_MODIFIED_ASC -> compareBy { it.lastModified }
            SortType.LAST_MODIFIED_DESC -> compareByDescending { it.lastModified }
            else -> compareBy { it.filename } // Default to sort by name asc
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
            changeScreen("connection")
        }
    }

    fun editConnection(connection: Connection) {
        viewModelScope.launch {
            _connection.update {
                connection
            }
            changeScreen("edit_connection")
        }
    }

    fun deleteConnection(connection: Connection) {
        viewModelScope.launch {
            connectionRepository.deleteConnection(connection)
            _connections.update {
                it.minus(connection)
            }
            changeScreen("connection")
        }
    }
}