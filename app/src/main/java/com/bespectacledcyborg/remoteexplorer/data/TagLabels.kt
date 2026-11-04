package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata

fun displayLabels(tags: List<LocalTagWithMetadata>): Map<String, String> {
    val withoutRoot = tags.associate { it.tag to it.label.substringAfter('/') }
    val uses = withoutRoot.values.groupingBy { it }.eachCount()
    return tags.associate { tag ->
        val short = withoutRoot.getValue(tag.tag)
        tag.tag to if (short.isBlank() || uses.getValue(short) > 1) tag.label else short
    }
}

data class TagChip(val tag: String, val label: String)

private fun LocalTagWithMetadata.groupKey(): String = canonical?.let { "group:$it" } ?: "tag:$tag"

fun listedChips(tags: List<LocalTagWithMetadata>): Map<String, TagChip> {
    val shown = displayLabels(tags)
    return tags.associate { it.groupKey() to TagChip(it.tag, shown.getValue(it.tag)) }
}

fun tagChips(own: List<LocalTagWithMetadata>, listed: Map<String, TagChip>): List<TagChip> {
    val distinct = own.distinctBy { it.groupKey() }
    val fallback = displayLabels(distinct)
    return distinct
        .map { listed[it.groupKey()] ?: TagChip(it.tag, fallback.getValue(it.tag)) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}
