package com.example.remoteexplorer.ui.components

import FallbackIcon
import VideoThumbnail
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import com.example.remoteexplorer.R
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.example.remoteexplorer.utils.Utils

@Composable
fun ImageIcon(
    explorerViewModel: ExplorerViewModel,
    fullPath: String?, modifier: Modifier = Modifier) {

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

    val context = LocalContext.current
    val model = when (iconType) {
        IconType.VIDEO -> ImageRequest.Builder(context)
            .data(VideoThumbnail(explorerViewModel, fullPath))
            .decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
            .build()
        else -> explorerViewModel.getUrl(fullPath)
    }

    AsyncImage(
        model = model,
        placeholder = painterResource(fallback),
        contentDescription = filename,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        fallback = painterResource(fallback),
        error = painterResource(fallback),
    )
}