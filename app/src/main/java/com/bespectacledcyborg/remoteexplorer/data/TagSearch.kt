package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.TagAliasPair

/**
 * Filters the tag list by what someone typed, alias groups included.
 *
 * A tag matches when its own name contains the query or when any other member of its group
 * does. That is the reason the alias layer exists for someone typing: パンスト should find
 * `female:pantyhose` and `clothes/pantyhose`, since opening either lists the same galleries.
 * Case-insensitive, substring, no normalisation beyond that.
 */
class TagSearch(pairs: List<TagAliasPair>) {
    private val aliases: Map<String, List<String>> =
        pairs.groupBy({ it.tag }, { it.alias })

    fun matches(tag: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return tag.contains(q, ignoreCase = true) ||
                aliases[tag].orEmpty().any { it.contains(q, ignoreCase = true) }
    }

    companion object {
        val NONE = TagSearch(emptyList())
    }
}
