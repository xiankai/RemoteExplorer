package com.example.greetingcard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.greetingcard.data.File
import com.example.greetingcard.data.FileRepository
import com.example.greetingcard.data.SambaFileDataSource
import com.example.greetingcard.data.ServerRepository
import com.example.greetingcard.data.ThumbnailRepository
import com.example.greetingcard.utils.PropertiesLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val _list = MutableStateFlow(listOf(File("test", "test")))
    val listState: StateFlow<List<File>> = _list.asStateFlow()

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
                val files = fileRepository.listFiles(newPath)
                _list.update {
                    files
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
}