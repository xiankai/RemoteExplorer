package com.example.greetingcard.ui

import com.example.greetingcard.data.File

data class ListUiState (
    val currentPath: String = "",
    var files: List<File> = emptyList()
)