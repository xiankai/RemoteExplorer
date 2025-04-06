package com.bespectacledcyborg.remoteexplorer.ui.components

import FallbackIcon
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bespectacledcyborg.remoteexplorer.R
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageOptions
import com.github.panpf.sketch.request.LoadState
import com.github.panpf.sketch.request.error
import com.github.panpf.sketch.request.placeholder
import io.sentry.Sentry
import io.sentry.SentryLevel

typealias GetImageUrl = (fullPath: String, thumbnail: Boolean) -> String

@Composable
fun ImageIcon(getImageUrl: GetImageUrl, fullPath: String?, modifier: Modifier = Modifier) {

    if (fullPath == null) {
        return FallbackIcon(null, modifier)
    }

    val filename = fullPath.substringAfterLast("/")
    val iconType = Utils.guessIconType(filename)

    val fallback =
            when (iconType) {
                IconType.IMAGE -> R.drawable.image_24px
                IconType.VIDEO -> R.drawable.movie_24px
                IconType.AUDIO -> R.drawable.music_note_24px
                IconType.TEXT -> R.drawable.description_24px
                else -> R.drawable.draft_24px
            }

    val state =
            rememberAsyncImageState(
                    ComposableImageOptions {
                        placeholder(fallback)
                        error(R.drawable.sentiment_very_dissatisfied_24px)
                    }
            )
    val loadState = state.loadState
     // Thumbnails only work for bitmaps atm
    val uri = getImageUrl(fullPath, true)
    when (loadState) {
        is LoadState.Error -> {
            Log.e("ImageIcon", "Failed to load: $fullPath, uri: $uri")
            Sentry.captureMessage(
                    "Failed to load",
                    SentryLevel.INFO,
                    {
                        it.setExtra("fullPath", fullPath)
                        it.setExtra("uri", uri)
                    }
            )
        }
        else -> {}
    }

    AsyncImage(
        uri = uri,
        state = state,
        contentDescription = filename,
        modifier = modifier,
    )
}
