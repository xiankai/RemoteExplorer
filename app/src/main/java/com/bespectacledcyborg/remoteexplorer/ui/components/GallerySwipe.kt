package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.SwipeStep
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.swipeStepFor

private val GALLERY_SWIPE_DISTANCE = 96.dp

fun Modifier.gallerySwipe(onSwipe: (SwipeStep) -> Unit): Modifier =
    pointerInput(onSwipe) {
        val threshold = GALLERY_SWIPE_DISTANCE.toPx()
        var dragX = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragX = 0f },
            onDragEnd = { swipeStepFor(dragX, threshold)?.let(onSwipe) },
            onDragCancel = { dragX = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                dragX += amount
            },
        )
    }
