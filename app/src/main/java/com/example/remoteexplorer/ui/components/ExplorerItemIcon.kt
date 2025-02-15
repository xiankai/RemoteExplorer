package com.example.remoteexplorer.ui.components

import FallbackIcon
import GalleryIcon
import SystemIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@Composable
fun ExplorerItemIcon(
    explorerViewModel: ExplorerViewModel,
    filename: String,
    type: FileType,
    thumbnails: List<String>,
    modifier: Modifier
) {
    when {
        thumbnails.size == 1 -> ImageIcon(explorerViewModel, thumbnails.firstOrNull(), modifier)
        thumbnails.size >= 2 -> GalleryIcon(explorerViewModel, thumbnails, modifier)
        type === FileType.CONNECTION -> SystemIcon(IconType.CONNECTION, modifier)
        type === FileType.ADD_CONNECTION -> SystemIcon(IconType.ADD_CONNECTION, modifier)
        type === FileType.FOLDER -> SystemIcon(IconType.FOLDER, modifier)
        else -> FallbackIcon(filename, modifier)
    }
}

enum class IconType {
    IMAGE, VIDEO, AUDIO, TEXT, FILE, FOLDER,
    CONNECTION, ADD_CONNECTION
}