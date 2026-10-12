package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.TagStatus
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CoverFinderTest {

    private class Tree(listed: Boolean = true) : CoverRows {
        private val folders = mutableMapOf<String, MutableList<LocalFile>>()
        private val listedFolders = mutableSetOf<String>()
        private val listAll = listed
        val listings = mutableListOf<String>()

        fun folder(path: String, modified: Long = 0): Tree {
            val parent = path.substringBeforeLast('/', "")
            folders.getOrPut(parent) { mutableListOf() } += row(path, FileType.FOLDER, modified)
            folders.getOrPut(path) { mutableListOf() }
            return this
        }

        fun image(path: String): Tree {
            folders.getOrPut(path.substringBeforeLast('/', "")) { mutableListOf() } +=
                row(path, FileType.FILE, 0)
            return this
        }

        private fun row(path: String, type: FileType, modified: Long) = LocalFile(
            connectionId = 1,
            parentPath = path.substringBeforeLast('/', ""),
            filename = path.substringAfterLast('/'),
            type = type,
            canHaveThumbnail = type == FileType.FILE,
            tagStatus = TagStatus.UNPROCESSED,
            thumbnailStatus = ThumbnailStatus.UNPROCESSED,
            lastModified = Date(modified),
        )

        override suspend fun isListed(connectionId: Int, folder: String) =
            listAll || folder in listedFolders

        override suspend fun list(connectionId: Int, folder: String) {
            listings += folder
            listedFolders += folder
        }

        override suspend fun images(connectionId: Int, folder: String, limit: Int) =
            folders[folder].orEmpty().filter { it.type == FileType.FILE }
                .sortedBy { it.filename }.take(limit).map { it.fullPath() }

        override suspend fun subfolders(connectionId: Int, folder: String) =
            folders[folder].orEmpty().filter { it.type == FileType.FOLDER }
    }

    @Test
    fun `a folder with images of its own keeps them`() = runTest {
        val tree = Tree().folder("g").image("g/2.jpg").image("g/1.jpg").folder("g/sub").image("g/sub/x.jpg")

        val cover = CoverFinder(tree).cover(1, "g", CoverOrder.NAME)

        assertEquals(listOf("g/1.jpg", "g/2.jpg"), cover.images)
        assertFalse(cover.borrowed)
    }

    @Test
    fun `a folder of folders borrows the first image of each of four sub-folders, in name order`() = runTest {
        val tree = Tree().folder("pixiv")
        listOf("e", "b", "a", "d", "c").forEach { tree.folder("pixiv/$it").image("pixiv/$it/0.jpg").image("pixiv/$it/1.jpg") }

        val cover = CoverFinder(tree).cover(1, "pixiv", CoverOrder.NAME)

        assertEquals(listOf("pixiv/a/0.jpg", "pixiv/b/0.jpg", "pixiv/c/0.jpg", "pixiv/d/0.jpg"), cover.images)
        assertTrue(cover.borrowed)
        assertTrue(cover.complete)
    }

    @Test
    fun `borrowing descends as deep as it has to`() = runTest {
        val tree = Tree().folder("a").folder("a/b").folder("a/b/c").folder("a/b/c/d").folder("a/b/c/d/e")
            .image("a/b/c/d/e/page.jpg")

        val cover = CoverFinder(tree).cover(1, "a", CoverOrder.NAME)

        assertEquals(listOf("a/b/c/d/e/page.jpg"), cover.images)
    }

    @Test
    fun `the newest order borrows from the most recently modified sub-folders`() = runTest {
        val tree = Tree().folder("root")
        tree.folder("root/old", modified = 1).image("root/old/0.jpg")
        tree.folder("root/new", modified = 3).image("root/new/0.jpg")
        tree.folder("root/mid", modified = 2).image("root/mid/0.jpg")

        val cover = CoverFinder(tree).cover(1, "root", CoverOrder.NEWEST)

        assertEquals(listOf("root/new/0.jpg", "root/mid/0.jpg", "root/old/0.jpg"), cover.images)
    }

    @Test
    fun `empty branches are passed over`() = runTest {
        val tree = Tree().folder("root").folder("root/_index").folder("root/agent-retrieved")
            .folder("root/pixiv").folder("root/pixiv/gallery").image("root/pixiv/gallery/0.jpg")

        val cover = CoverFinder(tree).cover(1, "root", CoverOrder.NAME)

        assertEquals(listOf("root/pixiv/gallery/0.jpg"), cover.images)
    }

    @Test
    fun `folders already listed cost no reads, and unlisted ones are listed once each`() = runTest {
        val tree = Tree(listed = false).folder("root")
        listOf("a", "b").forEach { tree.folder("root/$it").image("root/$it/0.jpg") }

        val cover = CoverFinder(tree).cover(1, "root", CoverOrder.NAME)

        assertEquals(listOf("root", "root/a", "root/b"), tree.listings)
        assertEquals(3, cover.listings)
        assertTrue(cover.complete)
    }

    @Test
    fun `running out of reads keeps what was found and says the cover is incomplete`() = runTest {
        val tree = Tree(listed = false).folder("root")
        listOf("a", "b", "c", "d").forEach { tree.folder("root/$it").image("root/$it/0.jpg") }

        val cover = CoverFinder(tree, listingBudget = 3).cover(1, "root", CoverOrder.NAME)

        assertEquals(listOf("root/a/0.jpg", "root/b/0.jpg"), cover.images)
        assertFalse(cover.complete)
        assertEquals(3, cover.listings)
    }

    @Test
    fun `a huge cached tree with no images stops at the visit cap`() = runTest {
        val tree = Tree().folder("root")
        repeat(50) { tree.folder("root/$it") }

        val cover = CoverFinder(tree, foldersVisited = 10).cover(1, "root", CoverOrder.NAME)

        assertTrue(cover.images.isEmpty())
        assertFalse(cover.complete)
    }
}
