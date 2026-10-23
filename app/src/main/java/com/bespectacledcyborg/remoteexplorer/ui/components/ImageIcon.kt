package com.bespectacledcyborg.remoteexplorer.ui.components

import FallbackIcon
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.bespectacledcyborg.remoteexplorer.R
import com.bespectacledcyborg.remoteexplorer.data.IMAGE_REQUEST_SLOTS
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetric
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetrics
import com.github.panpf.sketch.request.LoadState
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.AsyncImageState
import com.github.panpf.sketch.ability.progressIndicator
import com.github.panpf.sketch.painter.rememberRingProgressPainter
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageOptions
import com.github.panpf.sketch.request.error
import com.github.panpf.sketch.request.placeholder
import kotlinx.coroutines.delay

typealias GetImageUrl = (fullPath: String, thumbnail: Boolean) -> String

val LocalLoadMetrics = staticCompositionLocalOf { LoadMetrics.NONE }

internal class TileLoadTimer(
    private val metrics: LoadMetrics,
    private val attributes: Map<String, String> = emptyMap(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var startedAt: Long? = null
    private var abandoned = false
    private var failures = 0

    fun onLoadState(loadState: LoadState) = when (loadState) {
        is LoadState.Started -> started()
        is LoadState.Success -> succeeded(loadState.result.dataFrom.name.lowercase())
        is LoadState.Error -> failed(loadState.result.throwable)
        is LoadState.Canceled -> canceled()
        else -> Unit
    }

    fun started() {
        startedAt = clock()
        abandoned = false
    }

    fun succeeded(from: String) = finish("ok", from)

    fun failed(cause: Throwable? = null) {
        finish("error", "none", mapOf("error" to (cause?.javaClass?.simpleName ?: "unknown")))
        failures++
    }

    fun canceled() {
        startedAt = null
        abandoned = true
    }

    private fun finish(result: String, from: String, extra: Map<String, String> = emptyMap()) {
        if (abandoned) return
        val elapsed = startedAt?.let { clock() - it } ?: 0L
        startedAt = null
        abandoned = true
        metrics.record(
            LoadMetric.TILE_LOAD,
            elapsed,
            attributes + mapOf(
                "result" to result,
                "from" to from,
                "attempt" to (failures + 1).toString(),
            ) + extra,
        )
    }
}

internal object TileRetry {
    const val MAX_RETRIES = 3
    private const val FIRST_DELAY_MILLIS = 1_000L

    fun delayBefore(retry: Int, cause: Throwable?): Long? = when {
        retry >= MAX_RETRIES -> null
        cause.isServerAnswer() -> null
        else -> FIRST_DELAY_MILLIS shl retry
    }

    private fun Throwable?.isServerAnswer(): Boolean =
        this?.message?.startsWith("HTTP code error") == true
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
            val timer = TileLoadTimer(metrics, mapOf("client_slots" to IMAGE_REQUEST_SLOTS.toString()))
            state.onLoadState = timer::onLoadState
            onDispose { state.onLoadState = null }
        }
        val uri = getImageUrl(fullPath, true)
        val progressPainter = rememberRingProgressPainter()
        RetryFailedLoads(state, uri)

        AsyncImage(
            uri = uri,
            contentDescription = contentDescription,
            modifier = modifier.progressIndicator(state, progressPainter),
            state = state,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun RetryFailedLoads(state: AsyncImageState, uri: String) {
    var retries by remember(uri) { mutableIntStateOf(0) }
    val loadState = state.loadState
    LaunchedEffect(loadState) {
        when (loadState) {
            is LoadState.Success -> retries = 0
            is LoadState.Error -> {
                val wait = TileRetry.delayBefore(retries, loadState.result.throwable)
                    ?: return@LaunchedEffect
                delay(wait)
                retries++
                state.restart()
            }
            else -> Unit
        }
    }
}
