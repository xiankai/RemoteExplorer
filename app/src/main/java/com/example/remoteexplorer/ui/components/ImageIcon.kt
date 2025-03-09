package com.example.remoteexplorer.ui.components

import FallbackIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.remoteexplorer.R
import com.example.remoteexplorer.utils.Utils
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageOptions
import com.github.panpf.sketch.request.placeholder
import com.github.panpf.sketch.request.error

typealias GetImageUrl = (fullPath: String) -> String

@Composable
fun ImageIcon(
    getImageUrl: GetImageUrl,
    fullPath: String?,
    modifier: Modifier = Modifier
) {

    if (fullPath == null) {
        return FallbackIcon(null, modifier)
    }

    val filename = fullPath.substringAfterLast("/")
    val iconType = Utils.guessIconType(filename)

    val fallback = when (iconType) {
        IconType.IMAGE -> R.drawable.image_24px
        IconType.VIDEO -> R.drawable.movie_24px
        IconType.AUDIO -> R.drawable.music_note_24px
        IconType.TEXT -> R.drawable.description_24px
        else -> R.drawable.draft_24px
    }

    AsyncImage(
        uri = getImageUrl(fullPath),
        state = rememberAsyncImageState(ComposableImageOptions {
            placeholder(fallback)
            error(R.drawable.sentiment_very_dissatisfied_24px)
        }),
        contentDescription = filename,
        modifier = modifier,
    )
}