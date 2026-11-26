package com.bespectacledcyborg.remoteexplorer.ui.components

import kotlin.math.roundToInt

internal data class Section(val index: Int, val label: String)

internal data class FastScrollTarget(val index: Int, val label: String?)

internal data class ScrollMetrics(val first: Int, val visible: Int, val total: Int, val scrolling: Boolean)

internal fun <T> sectionsOf(entries: List<GridEntry<T>>, label: (String) -> String): List<Section> =
    entries.mapIndexedNotNull { index, entry ->
        (entry as? GridEntry.Header)?.let { Section(index, label(it.group)) }
    }

internal fun sectionAt(sections: List<Section>, index: Int): Section? =
    sections.lastOrNull { it.index <= index } ?: sections.firstOrNull()

internal fun canFastScroll(visible: Int, total: Int): Boolean = visible in 1 until total

internal fun thumbFraction(first: Int, visible: Int, total: Int): Float =
    if (canFastScroll(visible, total)) (first.toFloat() / (total - visible)).coerceIn(0f, 1f) else 0f

internal fun thumbLengthFraction(visible: Int, total: Int): Float =
    if (canFastScroll(visible, total)) visible.toFloat() / total else 1f

internal fun fastScrollTarget(fraction: Float, total: Int, sections: List<Section>): FastScrollTarget? {
    if (total <= 0) return null
    val index = (fraction.coerceIn(0f, 1f) * (total - 1)).roundToInt()
    val section = sectionAt(sections, index) ?: return FastScrollTarget(index, null)
    return FastScrollTarget(section.index, section.label)
}
