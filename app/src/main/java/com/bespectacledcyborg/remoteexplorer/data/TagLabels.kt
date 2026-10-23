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
