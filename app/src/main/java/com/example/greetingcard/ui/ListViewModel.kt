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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.util.Properties

class ListViewModel(
    private val defaultDispatcher: CoroutineDispatcher,
): ViewModel() {
    private lateinit var fileRepository: FileRepository
    private val _uiState = MutableStateFlow(ListUiState())
    val uiState: StateFlow<ListUiState> = _uiState.asStateFlow()

    fun connect() {
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
        }
    }

    fun listFiles(path: String = "") {
        viewModelScope.launch {
            println("Current Path: ${_uiState.value.currentPath}")
            val files = fileRepository.listFiles(path)
            println("Current Files: ${files}")
            _uiState.update { currentState ->
                currentState.copy(
                    currentPath = path,
                    files = files
                )
            }
        }
    }

    fun setPath(path: String) {
        viewModelScope.launch {
            _uiState.update { currentState ->
                currentState.copy(currentPath = path)
            }
            listFiles()
        }
    }
}