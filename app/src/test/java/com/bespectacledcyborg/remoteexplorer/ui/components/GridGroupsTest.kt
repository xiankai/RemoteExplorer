package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class GridGroupsTest {

    private fun file(parent: String, name: String) = LocalFile(
        connectionId = 1, parentPath = parent, filename = name, type = FileType.FOLDER,
        canHaveThumbnail = false, lastModified = Date(0),
    )

    @Test
    fun `clusters by parent path in path order, keeping the sorted order inside each`() {
        val sorted = listOf(
            file("b", "z"),
            file("A", "y"),
            file("b", "a"),
            file("", "root"),
            file("a", "x"),
            file("p/[10] ten", "t"),
            file("p/[9] nine", "n"),
        )

        val clustered = clusteredByParent(sorted).map { it.fullPath() }

        assertEquals(
            listOf("root", "A/y", "a/x", "b/z", "b/a", "p/[9] nine/n", "p/[10] ten/t"),
            clustered,
        )
    }

    @Test
    fun `clustering neither drops nor duplicates a row`() {
        val sorted = listOf(file("p", "g"), file("p", "g"), file("q", "h"))

        assertEquals(sorted.size, clusteredByParent(sorted).size)
    }

    @Test
    fun `ungrouped entries are plain tiles`() {
        val entries = gridEntries(listOf("a", "b"), columns = 3, groupOf = null)

        assertEquals(
            listOf(GridEntry.Tile("a", GroupCell.PLAIN), GridEntry.Tile("b", GroupCell.PLAIN)),
            entries,
        )
    }

    @Test
    fun `each group gets a header and is squared off to whole rows`() {
        val items = listOf("p/1", "p/2", "p/3", "p/4", "q/1")

        val entries = gridEntries(items, columns = 3) { it.substringBefore('/') }

        assertEquals(
            listOf(
                GridEntry.Header("p", first = true),
                GridEntry.Tile("p/1", GroupCell(true)),
                GridEntry.Tile("p/2", GroupCell(true)),
                GridEntry.Tile("p/3", GroupCell(true)),
                GridEntry.Tile("p/4", GroupCell(true, bottomStart = true)),
                GridEntry.Filler("p", 4, GroupCell(true)),
                GridEntry.Filler("p", 5, GroupCell(true, bottomEnd = true)),
                GridEntry.Header("q", first = false),
                GridEntry.Tile("q/1", GroupCell(true, bottomStart = true)),
                GridEntry.Filler("q", 1, GroupCell(true)),
                GridEntry.Filler("q", 2, GroupCell(true, bottomEnd = true)),
            ),
            entries,
        )
    }

    @Test
    fun `a group that fills its last row rounds that row's own tiles and adds no filler`() {
        val entries = gridEntries(listOf("p/1", "p/2", "p/3", "p/4"), columns = 2) {
            it.substringBefore('/')
        }

        assertEquals(
            listOf(
                GridEntry.Header("p", first = true),
                GridEntry.Tile("p/1", GroupCell(true)),
                GridEntry.Tile("p/2", GroupCell(true)),
                GridEntry.Tile("p/3", GroupCell(true, bottomStart = true)),
                GridEntry.Tile("p/4", GroupCell(true, bottomEnd = true)),
            ),
            entries,
        )
    }

    @Test
    fun `one column rounds both bottom corners of the last tile`() {
        val entries = gridEntries(listOf("p/1", "p/2"), columns = 1) { it.substringBefore('/') }

        assertEquals(
            GridEntry.Tile("p/2", GroupCell(true, bottomStart = true, bottomEnd = true)),
            entries.last(),
        )
    }

    @Test
    fun `every item comes back exactly once, in order`() {
        val items = listOf("p/1", "q/1", "q/2", "p/2")

        val tiles = gridEntries(items, columns = 2) { it.substringBefore('/') }
            .filterIsInstance<GridEntry.Tile<String>>()
            .map { it.item }

        assertEquals(items, tiles)
    }
}
