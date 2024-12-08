package com.example.greetingcard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.greetingcard.data.File
import com.example.greetingcard.data.FileRepository
import com.example.greetingcard.data.SambaFileDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.util.Properties

class ListViewModel(
    private val defaultDispatcher: CoroutineDispatcher,
): ViewModel() {
    private lateinit var fileRepository: FileRepository

    private val _path = MutableStateFlow("")
    val pathState: StateFlow<String> = _path.asStateFlow()
    private val _navigationStack = MutableStateFlow(listOf(""))
//    val navigationStackState: StateFlow<List<String>> = _navigationStack.asStateFlow()
    private val _list = MutableStateFlow(listOf(File("test", "test")))
    val listState: StateFlow<List<File>> = _list.asStateFlow()

    // On init:
    // 1. connect to source
    // 2. start watching `path` and then update `files` based on the value
    init {
        viewModelScope.launch {
            fileRepository = FileRepository(SambaFileDataSource(), defaultDispatcher)

            fileRepository.connect(
                "192.0.2.1",
//                "192.0.2.1",
                "user",
                "user",
                "",
                "share"
            )

            pathState.collect { newPath ->
                val files = fileRepository.listFiles(newPath)
                _list.update {
                    files
                }
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
        _path.update {
            _navigationStack.value.lastOrNull().orEmpty()
        }
        _navigationStack.update {
            it.dropLast(1)
        }
    }
}