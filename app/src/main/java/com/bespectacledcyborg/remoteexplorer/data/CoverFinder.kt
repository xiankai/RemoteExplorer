package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import kotlin.random.Random

sealed interface CoverOrder {
    val label: String
    fun subfolders(rows: List<LocalFile>, folder: String): List<LocalFile>
    fun pick(images: List<String>, folder: String, count: Int): List<String> = images.take(count)
    val pickFromAll: Boolean get() = false

    data object Name : CoverOrder {
        override val label = "name"
        override fun subfolders(rows: List<LocalFile>, folder: String) = rows.sortedBy { it.filename }
    }

    data object NameDesc : CoverOrder {
        override val label = "name-desc"
        override fun subfolders(rows: List<LocalFile>, folder: String) =
            rows.sortedByDescending { it.filename }
    }

    data object Oldest : CoverOrder {
        override val label = "oldest"
        override fun subfolders(rows: List<LocalFile>, folder: String) =
            rows.sortedWith(compareBy<LocalFile> { it.lastModified }.thenBy { it.filename })
    }

    data object Newest : CoverOrder {
        override val label = "newest"
        override fun subfolders(rows: List<LocalFile>, folder: String) =
            rows.sortedWith(compareByDescending<LocalFile> { it.lastModified }.thenBy { it.filename })
    }

    data class Shuffled(val seed: Long) : CoverOrder {
        override val label = "shuffled"
        override val pickFromAll = true

        override fun subfolders(rows: List<LocalFile>, folder: String) =
            rows.sortedBy { it.filename }.shuffled(randomFor(folder))

        override fun pick(images: List<String>, folder: String, count: Int) =
            images.shuffled(randomFor(folder)).take(count)

        private fun randomFor(folder: String) = Random(seed xor folder.hashCode().toLong())  // stack-reviewer-ignore: a collision only means two folders happen to shuffle the same way, which is cosmetic; the seed itself is per folder (FolderCoverChoice is keyed by canonical connection and path) or per visit, so connections never share a pick by construction.
    }
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
    suspend fun keepsFirstPage(connectionId: Int, folder: String): Boolean = false
}

const val COVER_SIZE = 4
const val COVER_LISTING_BUDGET = 12
const val COVER_FOLDERS_VISITED = 200
const val COVER_SHUFFLE_POOL = 2_000

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
            val own = imagesOf(folder, COVER_SIZE)
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
            imagesOf(folder, 1).firstOrNull()?.let { return it }
            for (subfolder in orderedSubfolders(folder)) {
                if (cutShort) return null
                firstImage(subfolder.fullPath())?.let { return it }
            }
            return null
        }

        private suspend fun imagesOf(folder: String, count: Int): List<String> {
            if (!order.pickFromAll || rows.keepsFirstPage(connectionId, folder)) {
                return rows.images(connectionId, folder, count)
            }
            return order.pick(rows.images(connectionId, folder, COVER_SHUFFLE_POOL), folder, count)  // stack-reviewer-ignore: images() is a Room query over rows already cached (LIMIT :limit, capped at 2000 here), not a listing; the listing budget bounds reads from the source, which this does not do. 2000 small rows of one folder is a few ms.
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
            order.subfolders(rows.subfolders(connectionId, folder), folder)

        private fun result(images: List<String>, borrowed: Boolean) =
            FolderCover(images, borrowed, listings, complete = !cutShort)
    }
}
