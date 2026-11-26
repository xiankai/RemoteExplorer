package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_TOUCH_WIDTH = 28.dp
private val THUMB_MIN_LENGTH = 48.dp

@Composable
internal fun FastScroller(
    metrics: () -> ScrollMetrics,
    sections: List<Section>,
    onJump: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = metrics()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentMetrics by rememberUpdatedState(metrics)
    val currentSections by rememberUpdatedState(sections)
    val currentOnJump by rememberUpdatedState(onJump)
    val scope = rememberCoroutineScope()

    if (!canFastScroll(current.visible, current.total) && dragFraction == null) return

    val dragging = dragFraction != null
    val alpha by animateFloatAsState(if (dragging || current.scrolling) 1f else 0.45f, label = "fastScrollAlpha")
    val barWidth by animateDpAsState(if (dragging) 8.dp else 4.dp, label = "fastScrollWidth")

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = maxOf(
            with(density) { THUMB_MIN_LENGTH.toPx() },
            trackPx * thumbLengthFraction(current.visible, current.total),
        ).coerceAtMost(trackPx)
        val travelPx = trackPx - thumbPx
        val currentTravel by rememberUpdatedState(travelPx)
        val fraction = dragFraction ?: thumbFraction(current.first, current.visible, current.total)
        val thumbTop = (travelPx * fraction).roundToInt()

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbTop) }
                .size(THUMB_TOUCH_WIDTH, with(density) { thumbPx.toDp() })
                .systemGestureExclusion()
                .pointerInput(Unit) {
                    var lastJump: Int? = null
                    var jump: Job? = null
                    fun jumpTo(fraction: Float) {
                        val target = fastScrollTarget(fraction, currentMetrics().total, currentSections)?.index
                        if (target == null || target == lastJump) return
                        lastJump = target
                        jump?.cancel()
                        jump = scope.launch { currentOnJump(target) }
                    }
                    detectVerticalDragGestures(
                        onDragStart = {
                            lastJump = null
                            val start = currentMetrics()
                            dragFraction = thumbFraction(start.first, start.visible, start.total)
                        },
                        onDragEnd = { dragFraction = null },
                        onDragCancel = { dragFraction = null },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            val travel = currentTravel
                            if (travel > 0f) {
                                val next = ((dragFraction ?: 0f) + delta / travel).coerceIn(0f, 1f)
                                dragFraction = next
                                jumpTo(next)
                            }
                        },
                    )
                }
                .alpha(alpha),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 3.dp)
                    .width(barWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            )
        }

        val label = dragFraction?.let { fastScrollTarget(it, current.total, sections)?.label }
        if (label != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbTop) }
                    .padding(end = THUMB_TOUCH_WIDTH + 8.dp)
                    .widthIn(max = 280.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
