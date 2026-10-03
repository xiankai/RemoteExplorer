package com.bespectacledcyborg.remoteexplorer.ui.components

import FallbackIcon
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.bespectacledcyborg.remoteexplorer.R
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.ability.progressIndicator
import com.github.panpf.sketch.painter.rememberRingProgressPainter
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageOptions
import com.github.panpf.sketch.request.error
import com.github.panpf.sketch.request.placeholder

typealias GetImageUrl = (fullPath: String, thumbnail: Boolean) -> String

@Composable
fun ImageIcon(
    getImageUrl: GetImageUrl,
    fullPath: String?,
    modifier: Modifier = Modifier,
    // Fit for a file's own tile, so the whole picture shows. A folder's cover collage crops
    // instead, so each cover fills its quarter rather than letterboxing inside it (#72).
    contentScale: ContentScale = ContentScale.Fit,
) {

    if (fullPath == null) {
        return FallbackIcon(null, modifier)
    }

    val filename = fullPath.substringAfterLast("/")
    val contentDescription = filename
    val iconType = Utils.guessIconType(filename)

    val fallback = when (iconType) {
        IconType.IMAGE -> R.drawable.image_24px
        IconType.VIDEO -> R.drawable.movie_24px
        IconType.AUDIO -> R.drawable.music_note_24px
        IconType.TEXT -> R.drawable.description_24px
        else -> R.drawable.draft_24px
    }

    if (!Utils.canHaveThumbnail(filename)) {
        Image(
            painter = painterResource(fallback),
            contentDescription = contentDescription,
            modifier = modifier
        )
    } else {
        val state = rememberAsyncImageState(ComposableImageOptions {
            placeholder(fallback)
            error(R.drawable.sentiment_very_dissatisfied_24px)
        })
        // Ask for the thumbnail, not the original. Only video used to, so every photo in the
        // grid was fetched whole across the wire and downsampled after the bytes had already
        // arrived — a folder of camera JPEGs is hundreds of megabytes to fill cells of 128 dp.
        val uri = getImageUrl(fullPath, true)
        val progressPainter = rememberRingProgressPainter()

        AsyncImage(
            uri = uri,
            contentDescription = contentDescription,
            modifier = modifier.progressIndicator(state, progressPainter),
            state = state,
            contentScale = contentScale,
        )
    }
}
