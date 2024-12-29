package com.example.remoteexplorer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.net.URLConnection
import com.example.remoteexplorer.R

enum class DisplayType {
    ICON, GALLERY, MANGA
}

@Composable
fun ListItemIcon(
    filename: String,
    url: String,
    modifier: Modifier,
    displayType: DisplayType = DisplayType.ICON
) {
    val mimeType = URLConnection.guessContentTypeFromName(filename) ?: "folder"

    val iconType = when {
        Regex("image/.*").matches(mimeType) -> IconType.IMAGE
        Regex("video/.*").matches(mimeType) -> IconType.VIDEO
        Regex("audio/.*").matches(mimeType) -> IconType.AUDIO
        Regex("text/.*").matches(mimeType) -> IconType.TEXT
        Regex("application/.*").matches(mimeType) -> IconType.FILE
        else -> IconType.FOLDER
    }

    when {
        displayType === DisplayType.MANGA -> MangaIcon(filename, url, iconType, modifier)
        displayType === DisplayType.GALLERY -> Text(text = "Manga")
        else ->
            SystemIcon(filename, iconType, modifier)
    }
}

enum class IconType {
    IMAGE, VIDEO, AUDIO, TEXT, FILE, FOLDER
}

@Composable
fun SystemIcon(filename: String, iconType: IconType,modifier: Modifier) {

    val icon = when(iconType) {
        IconType.IMAGE -> Icons.Default.Image
        IconType.VIDEO -> Icons.Default.VideoFile
        IconType.AUDIO -> Icons.Default.AudioFile
        IconType.TEXT -> Icons.Default.Description
        IconType.FILE -> Icons.Default.FileCopy
        else -> Icons.Default.Folder
    }

    Icon(
        icon,
        contentDescription = filename,
        modifier = modifier
    )
}

@Composable
fun MangaIcon(filename: String, url: String, iconType: IconType, modifier: Modifier) {
    val fallback = when (iconType) {
        IconType.IMAGE -> R.drawable.image_24px
        IconType.VIDEO -> R.drawable.movie_24px
        IconType.AUDIO -> R.drawable.music_note_24px
        IconType.TEXT -> R.drawable.description_24px
        IconType.FILE -> R.drawable.draft_24px
        else -> R.drawable.folder_24px
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