package com.bespectacledcyborg.remoteexplorer.ui.components

import FallbackIcon
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import com.github.panpf.sketch.ability.progressIndicator
import com.github.panpf.sketch.painter.rememberRingProgressPainter
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageOptions
import com.github.panpf.sketch.request.error
import com.github.panpf.sketch.request.placeholder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    fun succeeded(from: String) {
        finish("ok", from)
        failures = 0
    }

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

internal class TileRetrier(
    private val scope: CoroutineScope,
    private val restart: () -> Unit,
) {
    private var retries = 0
    private var pending: Job? = null

    fun onLoadState(loadState: LoadState) {
        when (loadState) {
            is LoadState.Success -> retries = 0
            is LoadState.Error -> failed(loadState.result.throwable)
            else -> Unit
        }
    }

    fun failed(cause: Throwable?) {
        val wait = TileRetry.delayBefore(retries, cause) ?: return
        retries++
        pending?.cancel()
        pending = scope.launch {
            delay(wait)
            restart()
        }
    }

    fun cancel() {
        pending?.cancel()
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
        val uri = getImageUrl(fullPath, true)
        val retryScope = rememberCoroutineScope()
        DisposableEffect(state, metrics, uri) {
            val timer = TileLoadTimer(metrics, mapOf("client_slots" to IMAGE_REQUEST_SLOTS.toString()))
            val retrier = TileRetrier(retryScope, state::restart)
            state.onLoadState = {
                timer.onLoadState(it)
                retrier.onLoadState(it)
            }
            onDispose {
                state.onLoadState = null
                retrier.cancel()
            }
        }
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
