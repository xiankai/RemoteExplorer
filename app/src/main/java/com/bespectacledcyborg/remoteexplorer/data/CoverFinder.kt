package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile

enum class CoverOrder(val subfolders: Comparator<LocalFile>) {
    NAME(compareBy { it.filename }),
    NAME_DESC(compareByDescending { it.filename }),
    OLDEST(compareBy<LocalFile> { it.lastModified }.thenBy { it.filename }),
    NEWEST(compareByDescending<LocalFile> { it.lastModified }.thenBy { it.filename }),
}

data class FolderCover(
    val images: List<String>,
    val borrowed: Boolean,
    val listings: Int,
    val complete: Boolean,
)

interface CoverRows {
    suspend fun isListed(connectionId: Int, folder: String): Boolean
    suspend fun list(connectionId: Int, folder: String)
    suspend fun images(connectionId: Int, folder: String, limit: Int): List<String>
    suspend fun subfolders(connectionId: Int, folder: String): List<LocalFile>
}

const val COVER_SIZE = 4
const val COVER_LISTING_BUDGET = 12
const val COVER_FOLDERS_VISITED = 200

class CoverFinder(
    private val rows: CoverRows,
    private val listingBudget: Int = COVER_LISTING_BUDGET,
    private val foldersVisited: Int = COVER_FOLDERS_VISITED,
) {
    suspend fun cover(connectionId: Int, folder: String, order: CoverOrder): FolderCover =
        Search(connectionId, order).run(folder)

    private inner class Search(private val connectionId: Int, private val order: CoverOrder) {
        private var listings = 0
        private var visited = 0
        private var cutShort = false

        suspend fun run(folder: String): FolderCover {
            if (!ensureListed(folder)) return result(emptyList(), borrowed = false)
            val own = rows.images(connectionId, folder, COVER_SIZE)
            if (own.isNotEmpty()) return result(own, borrowed = false)

            val borrowed = mutableListOf<String>()
            for (subfolder in orderedSubfolders(folder)) {
                if (borrowed.size == COVER_SIZE || cutShort) break
                firstImage(subfolder.fullPath())?.let { borrowed += it }
            }
            return result(borrowed, borrowed = borrowed.isNotEmpty())
        }

        private suspend fun firstImage(folder: String): String? {
            if (++visited > foldersVisited) {
                cutShort = true
                return null
            }
            if (!ensureListed(folder)) return null
            rows.images(connectionId, folder, 1).firstOrNull()?.let { return it }
            for (subfolder in orderedSubfolders(folder)) {
                if (cutShort) return null
                firstImage(subfolder.fullPath())?.let { return it }
            }
            return null
        }

        private suspend fun ensureListed(folder: String): Boolean {
            if (rows.isListed(connectionId, folder)) return true
            if (listings >= listingBudget) {
                cutShort = true
                return false
            }
            listings++
            rows.list(connectionId, folder)
            return true
        }

        private suspend fun orderedSubfolders(folder: String): List<LocalFile> =
            rows.subfolders(connectionId, folder).sortedWith(order.subfolders)

        private fun result(images: List<String>, borrowed: Boolean) =
            FolderCover(images, borrowed, listings, complete = !cutShort)
    }
}
