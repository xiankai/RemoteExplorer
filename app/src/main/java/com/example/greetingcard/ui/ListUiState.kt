package com.example.greetingcard.ui

import com.example.greetingcard.data.File
import java.nio.file.Path
import kotlin.io.path.Path

data class ListUiState (
    val currentPath: String = "",
    var files: List<File> = listOf(
        File("filename", "uri"),
        File("filename2", "uri2"),
        File("filename3", "uri3"),
    ),
)