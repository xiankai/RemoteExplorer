package com.example.remoteexplorer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.remoteexplorer.data.File
import com.example.remoteexplorer.data.FileRepository
import com.example.remoteexplorer.data.SambaFileDataSource
import com.example.remoteexplorer.data.ServerRepository
import com.example.remoteexplorer.data.ThumbnailRepository
import com.example.remoteexplorer.utils.PropertiesLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortType {
    NAME_ASC,
    NAME_DESC,
    LAST_MODIFIED_ASC,
    LAST_MODIFIED_DESC
}

class ListViewModel(
    application: Application,
    private val defaultDispatcher: CoroutineDispatcher,
): AndroidViewModel(application) {
    private lateinit var fileRepository: FileRepository
    private lateinit var serverRepository: ServerRepository
    private lateinit var thumbnailRepository: ThumbnailRepository

    private val _path = MutableStateFlow("")
    val pathState: StateFlow<String> = _path.asStateFlow()
    private val _navigationStack = MutableStateFlow(listOf(""))
    private val _list: MutableStateFlow<List<File>> = MutableStateFlow(emptyList())
    val listState: StateFlow<List<File>> = _list.asStateFlow()

    private val _search: MutableStateFlow<String?> = MutableStateFlow(null)
    val searchState: StateFlow<String?> = _search.asStateFlow()
    private val _sort = MutableStateFlow(SortType.NAME_ASC)
    val sortState: StateFlow<SortType> = _sort.asStateFlow()

    // On init:
    // 1. connect to source
    // 2. start watching `path` and then update `files` based on the value
    init {
        val smbFileDataSource = SambaFileDataSource()
        viewModelScope.launch {
            fileRepository = FileRepository(smbFileDataSource, defaultDispatcher)
            fileRepository.connect(
                PropertiesLoader.getProperty("hostname", ""),
                PropertiesLoader.getProperty("username", ""),
                PropertiesLoader.getProperty("password", ""),
                PropertiesLoader.getProperty("domain", ""),
                PropertiesLoader.getProperty("share", ""),
            )

            pathState.collect { newPath ->
                val files = fileRepository.listFiles(newPath, searchState.value)
                _list.update {
                    files.sortedWith(getSortFunction(sortState.value))
                }
            }
        }
        viewModelScope.launch {
            serverRepository = ServerRepository(smbFileDataSource, defaultDispatcher)
            serverRepository.startup()
        }
        viewModelScope.launch {
            thumbnailRepository = ThumbnailRepository(application.applicationContext, smbFileDataSource, defaultDispatcher)
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
}