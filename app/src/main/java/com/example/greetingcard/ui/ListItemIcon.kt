package com.example.greetingcard.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.net.URLConnection

@Composable
fun ListItemIcon(filename: String, modifier: Modifier) {
    val mimeType = URLConnection.guessContentTypeFromName(filename) ?: "folder"
    println("mimeType of $filename is $mimeType")
    println(mimeType)

    val (icon, contentDescription) = when {
        Regex("image/.*").matches(mimeType) -> Pair(Icons.Default.Image, "image")
        Regex("video/.*").matches(mimeType) -> Pair(Icons.Default.VideoFile, "video")
        Regex("audio/.*").matches(mimeType) -> Pair(Icons.Default.AudioFile, "audio")
        Regex("text/.*").matches(mimeType) -> Pair(Icons.Default.Description, "text")
        Regex("application/.*").matches(mimeType) -> Pair(Icons.Default.FileCopy, "file")
        else -> Pair(Icons.Default.Folder, "folder")
    }

    Icon(
        icon,
        contentDescription,
        modifier
    )
}