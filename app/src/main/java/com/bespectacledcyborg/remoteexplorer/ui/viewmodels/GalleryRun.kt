package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

enum class SwipeStep(val offset: Int) { PREVIOUS(-1), NEXT(1) }

fun swipeStepFor(dragX: Float, threshold: Float): SwipeStep? = when {
    dragX <= -threshold -> SwipeStep.NEXT
    dragX >= threshold -> SwipeStep.PREVIOUS
    else -> null
}

data class GalleryRun(val origin: String, val galleries: List<String>) {
    fun neighbour(current: String, step: SwipeStep): String? {
        val index = galleries.indexOf(current)
        if (index < 0) return null
        return galleries.getOrNull(index + step.offset)
    }

    fun isOpenFrom(stack: List<String>): Boolean =
        stack.size >= 2 && stack[stack.lastIndex - 1] == origin && stack.last() in galleries
}
