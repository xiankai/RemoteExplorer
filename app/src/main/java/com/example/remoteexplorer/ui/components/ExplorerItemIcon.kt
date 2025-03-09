package com.example.remoteexplorer.ui.components

import FallbackIcon
import GalleryIcon
import SystemIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.remoteexplorer.data.db.FileType

@Composable
fun ExplorerItemIcon(
    getImageUrl: GetImageUrl,
    filename: String,
    type: FileType,
    thumbnails: List<String>,
    modifier: Modifier
) = when {
//        thumbnails.size == 1 -> ImageIcon(getImageUrl, thumbnails.firstOrNull(), modifier)
    thumbnails.isNotEmpty() -> GalleryIcon(getImageUrl, thumbnails, modifier)
    type === FileType.CONNECTION -> SystemIcon(IconType.CONNECTION, modifier)
    type === FileType.ADD_CONNECTION -> SystemIcon(IconType.ADD_CONNECTION, modifier)
    type === FileType.FOLDER -> SystemIcon(IconType.FOLDER, modifier)
    else -> FallbackIcon(filename, modifier)
}

enum class IconType {
    IMAGE, VIDEO, AUDIO, TEXT, FILE, FOLDER, TAG,
    CONNECTION, ADD_CONNECTION
}