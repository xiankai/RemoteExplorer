package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.FolderSort
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSortsTest {
    private val share = 1
    private val local = 2

    private fun sorts(vararg rows: FolderSort, pairings: Pairings = Pairings.NONE) =
        FolderSorts(rows.toList(), SortType.NAME_ASC, pairings)

    @Test
    fun `a folder with nothing chosen anywhere follows the default`() {
        val choice = sorts().choiceAt(share, "a/b")
        assertEquals(SortType.NAME_ASC, choice.current)
        assertFalse(choice.chosenHere)
    }

    @Test
    fun `a folder's choice reaches every folder below it`() {
        val sorts = sorts(FolderSort(share, "a", SortType.LAST_MODIFIED_DESC))
        assertEquals(SortType.LAST_MODIFIED_DESC, sorts.choiceAt(share, "a/b/c").current)
        assertEquals(SortType.NAME_ASC, sorts.choiceAt(share, "").current)
    }

    @Test
    fun `the nearest choice wins and the one above is what it would inherit`() {
        val sorts = sorts(
            FolderSort(share, "", SortType.MOST_VIEWED),
            FolderSort(share, "a/b", SortType.NAME_DESC),
        )
        val choice = sorts.choiceAt(share, "a/b")
        assertEquals(SortType.NAME_DESC, choice.current)
        assertEquals(SortType.MOST_VIEWED, choice.inherited)
        assertTrue(choice.chosenHere)
        assertEquals(SortType.NAME_DESC, sorts.choiceAt(share, "a/b/c").current)
        assertEquals(SortType.MOST_VIEWED, sorts.choiceAt(share, "a").current)
    }

    @Test
    fun `a sibling whose name starts the same is not inside the folder`() {
        val sorts = sorts(FolderSort(share, "Photos", SortType.NAME_DESC))
        assertEquals(SortType.NAME_ASC, sorts.choiceAt(share, "Photos2").current)
    }

    @Test
    fun `a choice belongs to its connection`() {
        val sorts = sorts(FolderSort(share, "", SortType.NAME_DESC))
        assertEquals(SortType.NAME_ASC, sorts.choiceAt(3, "a").current)
    }

    @Test
    fun `a tag listing does not inherit the share root's choice`() {
        val sorts = sorts(FolderSort(share, "", SortType.NAME_DESC))
        val choice = sorts.choiceAt(share, "${tagPrefix}cat")
        assertEquals(SortType.NAME_ASC, choice.current)
        assertTrue(choice.isTopLevel)
    }

    @Test
    fun `a paired local copy shares its remote folder's choice`() {
        val pairings = Pairings(listOf(Pairing(local, "", share, "Photos")))
        val sorts = sorts(FolderSort(share, "Photos/2024", SortType.NAME_DESC), pairings = pairings)
        assertEquals(SortType.NAME_DESC, sorts.choiceAt(local, "2024/trip").current)
        assertEquals(FileIdentity(share, "Photos/2024"), sorts.identityOf(local, "2024"))
    }

    @Test
    fun `a tag listing on a paired local root stays under that root`() {
        val pairings = Pairings(listOf(Pairing(local, "", share, "Photos")))
        val sorts = sorts(pairings = pairings)
        assertEquals(FileIdentity(local, "${tagPrefix}cat"), sorts.identityOf(local, "${tagPrefix}cat"))
    }

    @Test
    fun `newest additions defaults to newest first and can be changed`() {
        assertEquals(SortType.LAST_MODIFIED_DESC, sorts().choiceAt(share, newestAdditionsPath).current)

        val chosen = sorts(FolderSort(share, newestAdditionsPath, SortType.NAME_ASC))
        assertEquals(SortType.NAME_ASC, chosen.choiceAt(share, newestAdditionsPath).current)
    }
}
