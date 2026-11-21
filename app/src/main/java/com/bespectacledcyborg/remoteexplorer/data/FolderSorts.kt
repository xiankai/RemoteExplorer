package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.FolderSort

data class FolderSortChoice(
    val current: SortType,
    val inherited: SortType,
    val chosenHere: Boolean,
    val isTopLevel: Boolean,
    val isLeaf: Boolean,
)

class FolderSorts(
    rows: List<FolderSort>,
    private val default: SortType,
    private val pairings: Pairings,
) {
    private val chosen: Map<FileIdentity, SortType> =
        rows.associate { FileIdentity(it.connectionId, it.path) to it.sort }

    fun identityOf(connectionId: ConnectionId, path: String): FileIdentity =
        if (isPseudoFolder(path)) {
            FileIdentity(connectionId, path)
        } else {
            pairings.canonicalize(connectionId, Pairings.normalize(path))
        }

    fun choiceAt(connectionId: ConnectionId, path: String, hasSubfolders: Boolean = true): FolderSortChoice {
        val lineage = lineageOf(path)
        val own = chosen[identityOf(connectionId, lineage.first())]
        val isLeaf = !hasSubfolders && !isPseudoFolder(path)
        val inherited = if (isLeaf) LEAF_SORT else inheritedAlong(connectionId, lineage, path)
        return FolderSortChoice(
            current = own ?: inherited,
            inherited = inherited,
            chosenHere = own != null,
            isTopLevel = lineage.size == 1,
            isLeaf = isLeaf,
        )
    }

    private fun inheritedAlong(connectionId: ConnectionId, lineage: List<String>, path: String): SortType =
        lineage.drop(1)
            .firstNotNullOfOrNull { chosen[identityOf(connectionId, it)] }
            ?: if (path == newestAdditionsPath) SortType.ADDED_DESC else default

    private fun lineageOf(path: String): List<String> {
        if (isPseudoFolder(path)) return listOf(path)
        val segments = Pairings.normalize(path).split('/').filter { it.isNotEmpty() }
        return (segments.size downTo 0).map { segments.take(it).joinToString("/") }  // stack-reviewer-ignore: intended; a sort chosen at a connection's root applies to every folder below it, as the owner asked, and FolderSortsTest covers it ("the nearest choice wins")
    }

    companion object {
        val LEAF_SORT = SortType.NAME_ASC
        val NONE = FolderSorts(emptyList(), SortType.NAME_ASC, Pairings.NONE)
    }
}
