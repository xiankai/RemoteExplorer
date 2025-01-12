package com.example.remoteexplorer.ui.components

import GalleryIcon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.example.remoteexplorer.R
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.utils.Utils

@Composable
fun ExplorerItemIcon(
    filename: String,
    type: FileType,
    urls: List<String>,
    modifier: Modifier
) {
    val iconType = Utils.guessIconType(filename)

    when {
        urls.size == 1 -> MangaIcon(filename, urls.firstOrNull(), iconType, modifier)
        urls.size >= 2 -> GalleryIcon(thumbnailUrls = urls, modifier = modifier)
        type === FileType.CONNECTION -> SystemIcon(filename, IconType.CONNECTION, modifier)
        type === FileType.ADD_CONNECTION -> SystemIcon(filename, IconType.ADD_CONNECTION, modifier)
        else ->
            SystemIcon(filename, iconType, modifier)
    }
}

enum class IconType {
    IMAGE, VIDEO, AUDIO, TEXT, FILE, FOLDER,
    CONNECTION, ADD_CONNECTION
}

@Composable
fun SystemIcon(filename: String, iconType: IconType, modifier: Modifier) {

    val icon = when(iconType) {
        IconType.CONNECTION-> Icons.Default.FolderShared
        IconType.ADD_CONNECTION -> Icons.Default.CreateNewFolder
        IconType.IMAGE -> Icons.Default.Image
        IconType.VIDEO -> Icons.Default.VideoFile
        IconType.AUDIO -> Icons.Default.AudioFile
        IconType.TEXT -> Icons.Default.Description
        else -> Icons.AutoMirrored.Default.InsertDriveFile
    }

    Icon(
        icon,
        contentDescription = filename,
        modifier = modifier
    )
}

@Composable
fun MangaIcon(filename: String, url: String?, iconType: IconType, modifier: Modifier) {
    val fallback = when (iconType) {
        IconType.IMAGE -> R.drawable.image_24px
        IconType.VIDEO -> R.drawable.movie_24px
        IconType.AUDIO -> R.drawable.music_note_24px
        IconType.TEXT -> R.drawable.description_24px
        else -> R.drawable.draft_24px
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .build(),
        placeholder = painterResource(fallback),
        contentDescription = filename,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        fallback = painterResource(fallback),
        error = painterResource(fallback),
    )
}