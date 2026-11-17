package com.bespectacledcyborg.remoteexplorer.ui.screens

import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.Utils

data class ShownListing(val entries: List<LocalFile>, val emptyMessage: String?) {
    companion object {
        val NONE = ShownListing(emptyList(), null)
    }
}

fun List<LocalFile>.keepingTypes(
    types: List<IconType>,
    iconTypeOf: (String) -> IconType = Utils::guessIconType,
): List<LocalFile> {
    if (types.isEmpty()) return this
    return filter { it.type == FileType.FOLDER || iconTypeOf(it.filename) in types }
}

fun List<LocalFile>.matchingSearch(search: String): List<LocalFile> {
    if (search.isEmpty()) return this
    return filter { it.filename.contains(search, ignoreCase = true) }
}

fun emptyResultMessage(
    search: String,
    types: List<IconType>,
    folderIsEmpty: Boolean,
    resultIsEmpty: Boolean,
): String? {
    if (folderIsEmpty || !resultIsEmpty) return null
    val kinds = types.joinToString(" or ") { it.name.lowercase() }
    return when {
        search.isNotEmpty() && kinds.isNotEmpty() -> "No $kinds matches \"$search\""
        search.isNotEmpty() -> "Nothing matches \"$search\""
        kinds.isNotEmpty() -> "No $kinds here"
        else -> null
    }
}
