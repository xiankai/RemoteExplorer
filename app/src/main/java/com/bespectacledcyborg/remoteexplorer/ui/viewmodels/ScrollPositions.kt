package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

class ScrollPositions {
    private val stack = mutableListOf(ScrollPosition())

    val top: ScrollPosition
        get() = stack.lastOrNull() ?: ScrollPosition()

    fun push() {
        stack.add(ScrollPosition())
    }

    fun pop() {
        stack.removeLastOrNull()
    }

    fun reset() {
        stack.clear()
        stack.add(ScrollPosition())
    }

    fun recordTop(position: ScrollPosition) {
        if (stack.isNotEmpty()) stack[stack.lastIndex] = position
    }
}
