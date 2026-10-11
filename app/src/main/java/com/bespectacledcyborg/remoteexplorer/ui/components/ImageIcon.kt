package com.bespectacledcyborg.remoteexplorer.ui.components

import FallbackIcon
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.bespectacledcyborg.remoteexplorer.R
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetric
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetrics
import com.github.panpf.sketch.request.LoadState
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.ability.progressIndicator
import com.github.panpf.sketch.painter.rememberRingProgressPainter
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageOptions
import com.github.panpf.sketch.request.error
import com.github.panpf.sketch.request.placeholder

typealias GetImageUrl = (fullPath: String, thumbnail: Boolean) -> String

val LocalLoadMetrics = staticCompositionLocalOf { LoadMetrics.NONE }

internal class TileLoadTimer(
    private val metrics: LoadMetrics,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var startedAt: Long? = null

    fun onLoadState(loadState: LoadState) = when (loadState) {
        is LoadState.Started -> started()
        is LoadState.Success -> succeeded(loadState.result.dataFrom.name.lowercase())
        is LoadState.Error -> failed()
        is LoadState.Canceled -> canceled()
        else -> Unit
    }

    fun started() {
        startedAt = clock()
    }

    fun succeeded(from: String) = finish("ok", from)

    fun failed() = finish("error", "none")

    fun canceled() {
        startedAt = null
    }

    private fun finish(result: String, from: String) {
        val started = startedAt ?: return
        startedAt = null
        metrics.record(
            LoadMetric.TILE_LOAD,
            clock() - started,
            mapOf("result" to result, "from" to from),
        )
    }
}

@Composable
fun ImageIcon(
    getImageUrl: GetImageUrl,
    fullPath: String?,
    modifier: Modifier = Modifier,
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
        val metrics = LocalLoadMetrics.current
        DisposableEffect(state, metrics) {
            val timer = TileLoadTimer(metrics)
            state.onLoadState = timer::onLoadState
            onDispose { state.onLoadState = null }
        }
        val uri = getImageUrl(fullPath, true)
        val progressPainter = rememberRingProgressPainter()

        AsyncImage(
            uri = uri,
            contentDescription = contentDescription,
            modifier = modifier.progressIndicator(state, progressPainter),
            state = state,
            contentScale = ContentScale.Crop,
        )
    }
}
