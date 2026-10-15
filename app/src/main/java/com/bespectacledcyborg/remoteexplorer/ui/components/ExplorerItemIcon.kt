package com.bespectacledcyborg.remoteexplorer.ui.components

import FallbackIcon
import GalleryIcon
import SystemIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.utils.Utils

@Composable
fun ExplorerItemIcon(
    getImageUrl: GetImageUrl,
    filename: String,
    type: FileType,
    thumbnails: List<String>,
    cover: CoverMode,
    modifier: Modifier
) = when {
    type === FileType.FOLDER && thumbnails.isNotEmpty() -> GalleryIcon(getImageUrl, thumbnails, cover, modifier)
    thumbnails.size == 1 -> ImageIcon(getImageUrl, thumbnails.firstOrNull(), modifier)
    type === FileType.CONNECTION -> SystemIcon(IconType.CONNECTION, modifier)
    type === FileType.ADD_CONNECTION -> SystemIcon(IconType.ADD_CONNECTION, modifier)
    type === FileType.FOLDER -> SystemIcon(IconType.FOLDER, modifier)
    else -> FallbackIcon(filename, modifier)
}

fun drawsPicture(type: FileType, thumbnails: List<String>): Boolean = when {
    type === FileType.FOLDER && thumbnails.isNotEmpty() -> true
    thumbnails.size == 1 -> Utils.canHaveThumbnail(thumbnails.first().substringAfterLast("/"))
    else -> false
}

enum class IconType {
    IMAGE, VIDEO, AUDIO, TEXT, FILE, FOLDER, SVG,
    CONNECTION, ADD_CONNECTION
}