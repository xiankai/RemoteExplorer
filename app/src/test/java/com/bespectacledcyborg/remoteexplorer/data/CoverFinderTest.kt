package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.TagStatus
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.utils.NaturalOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CoverFinderTest {

    private class Tree(listed: Boolean = true, private val manga: Set<String> = emptySet()) : CoverRows {
        override suspend fun keepsFirstPage(connectionId: Int, folder: String) = folder in manga

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
                .sortedWith(compareBy(NaturalOrder) { it.filename }).take(limit).map { it.fullPath() }

        override suspend fun subfolders(connectionId: Int, folder: String) =
            folders[folder].orEmpty().filter { it.type == FileType.FOLDER }
    }

    @Test
    fun `a folder with images of its own keeps them`() = runTest {
        val tree = Tree().folder("g").image("g/2.jpg").image("g/1.jpg").folder("g/sub").image("g/sub/x.jpg")

        val cover = CoverFinder(tree).cover(1, "g", CoverOrder.Name)

        assertEquals(listOf("g/1.jpg", "g/2.jpg"), cover.images)
        assertFalse(cover.borrowed)
    }

    @Test
    fun `a folder of folders borrows the first image of each of four sub-folders, in name order`() = runTest {
        val tree = Tree().folder("pixiv")
        listOf("e", "b", "a", "d", "c").forEach { tree.folder("pixiv/$it").image("pixiv/$it/0.jpg").image("pixiv/$it/1.jpg") }

        val cover = CoverFinder(tree).cover(1, "pixiv", CoverOrder.Name)

        assertEquals(listOf("pixiv/a/0.jpg", "pixiv/b/0.jpg", "pixiv/c/0.jpg", "pixiv/d/0.jpg"), cover.images)
        assertTrue(cover.borrowed)
        assertTrue(cover.complete)
    }

    @Test
    fun `sub-folders numbered without padding borrow in numeric order, either way round`() = runTest {
        val tree = Tree().folder("series")
        listOf("10", "2", "100", "1", "3").forEach { tree.folder("series/$it").image("series/$it/0.jpg") }

        val ascending = CoverFinder(tree).cover(1, "series", CoverOrder.Name)
        val descending = CoverFinder(tree).cover(1, "series", CoverOrder.NameDesc)

        assertEquals(listOf("series/1/0.jpg", "series/2/0.jpg", "series/3/0.jpg", "series/10/0.jpg"), ascending.images)
        assertEquals(listOf("series/100/0.jpg", "series/10/0.jpg", "series/3/0.jpg", "series/2/0.jpg"), descending.images)
    }

    @Test
    fun `borrowing descends as deep as it has to`() = runTest {
        val tree = Tree().folder("a").folder("a/b").folder("a/b/c").folder("a/b/c/d").folder("a/b/c/d/e")
            .image("a/b/c/d/e/page.jpg")

        val cover = CoverFinder(tree).cover(1, "a", CoverOrder.Name)

        assertEquals(listOf("a/b/c/d/e/page.jpg"), cover.images)
    }

    @Test
    fun `the newest order borrows from the most recently modified sub-folders`() = runTest {
        val tree = Tree().folder("root")
        tree.folder("root/old", modified = 1).image("root/old/0.jpg")
        tree.folder("root/new", modified = 3).image("root/new/0.jpg")
        tree.folder("root/mid", modified = 2).image("root/mid/0.jpg")

        val cover = CoverFinder(tree).cover(1, "root", CoverOrder.Newest)

        assertEquals(listOf("root/new/0.jpg", "root/mid/0.jpg", "root/old/0.jpg"), cover.images)
    }

    @Test
    fun `empty branches are passed over`() = runTest {
        val tree = Tree().folder("root").folder("root/_index").folder("root/agent-retrieved")
            .folder("root/pixiv").folder("root/pixiv/gallery").image("root/pixiv/gallery/0.jpg")

        val cover = CoverFinder(tree).cover(1, "root", CoverOrder.Name)

        assertEquals(listOf("root/pixiv/gallery/0.jpg"), cover.images)
    }

    @Test
    fun `folders already listed cost no reads, and unlisted ones are listed once each`() = runTest {
        val tree = Tree(listed = false).folder("root")
        listOf("a", "b").forEach { tree.folder("root/$it").image("root/$it/0.jpg") }

        val cover = CoverFinder(tree).cover(1, "root", CoverOrder.Name)

        assertEquals(listOf("root", "root/a", "root/b"), tree.listings)
        assertEquals(3, cover.listings)
        assertTrue(cover.complete)
    }

    @Test
    fun `running out of reads keeps what was found and says the cover is incomplete`() = runTest {
        val tree = Tree(listed = false).folder("root")
        listOf("a", "b", "c", "d").forEach { tree.folder("root/$it").image("root/$it/0.jpg") }

        val cover = CoverFinder(tree, listingBudget = 3).cover(1, "root", CoverOrder.Name)

        assertEquals(listOf("root/a/0.jpg", "root/b/0.jpg"), cover.images)
        assertFalse(cover.complete)
        assertEquals(3, cover.listings)
    }

    private fun gallery(pages: Int) = Tree().folder("g").also { tree ->
        repeat(pages) { tree.image("g/%02d.jpg".format(it)) }
    }

    @Test
    fun `a shuffled cover is the same for the same seed`() = runTest {
        val finder = CoverFinder(gallery(12))

        assertEquals(
            finder.cover(1, "g", CoverOrder.Shuffled(1)).images,
            finder.cover(1, "g", CoverOrder.Shuffled(1)).images,
        )
    }

    @Test
    fun `two seeds pick two specific covers from the whole folder`() = runTest {
        val finder = CoverFinder(gallery(12))

        assertEquals(SEED_1_COVER, finder.cover(1, "g", CoverOrder.Shuffled(1)).images)
        assertEquals(SEED_2_COVER, finder.cover(1, "g", CoverOrder.Shuffled(2)).images)
    }

    @Test
    fun `a manga gallery under a shuffled parent still lends its first page`() = runTest {
        val tree = Tree(manga = setOf("root/manga")).folder("root")
        tree.folder("root/manga")
        repeat(10) { tree.image("root/manga/%02d.jpg".format(it)) }

        val covers = (1L..20L).map { CoverFinder(tree).cover(1, "root", CoverOrder.Shuffled(it)).images }

        assertEquals(setOf(listOf("root/manga/00.jpg")), covers.toSet())
    }

    @Test
    fun `a manga folder's own pages are never shuffled`() = runTest {
        val tree = Tree(manga = setOf("g")).folder("g")
        repeat(12) { tree.image("g/%02d.jpg".format(it)) }

        val cover = CoverFinder(tree).cover(1, "g", CoverOrder.Shuffled(1)).images

        assertEquals(listOf("g/00.jpg", "g/01.jpg", "g/02.jpg", "g/03.jpg"), cover)
    }

    @Test
    fun `a huge cached tree with no images stops at the visit cap`() = runTest {
        val tree = Tree().folder("root")
        repeat(50) { tree.folder("root/$it") }

        val cover = CoverFinder(tree, foldersVisited = 10).cover(1, "root", CoverOrder.Name)

        assertTrue(cover.images.isEmpty())
        assertFalse(cover.complete)
    }

    private companion object {
        val SEED_1_COVER = listOf("g/09.jpg", "g/06.jpg", "g/03.jpg", "g/04.jpg")
        val SEED_2_COVER = listOf("g/09.jpg", "g/11.jpg", "g/08.jpg", "g/03.jpg")
    }
}
