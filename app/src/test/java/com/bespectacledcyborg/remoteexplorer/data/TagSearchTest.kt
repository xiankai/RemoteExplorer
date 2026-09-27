package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.TagAliasPair
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSearchTest {

    // The live pantyhose group, as pairs: every member beside every other.
    private val pantyhose = listOf("clothes/pantyhose", "female:pantyhose", "pantyhose", "パンスト")
    private val search = TagSearch(
        pantyhose.flatMap { tag -> pantyhose.filter { it != tag }.map { TagAliasPair(tag, it) } }
    )

    @Test
    fun `a blank query matches everything`() {
        assertTrue(search.matches("anything", ""))
        assertTrue(search.matches("anything", "   "))
    }

    @Test
    fun `a tag matches on its own name, ignoring case and surrounding space`() {
        assertTrue(search.matches("female:Glasses", " glasses "))
        assertFalse(search.matches("female:ponytail", "glasses"))
    }

    /** The reason for the alias layer when typing: the Japanese finds the English tags. */
    @Test
    fun `a tag matches when another member of its group does`() {
        assertTrue(search.matches("female:pantyhose", "パンスト"))
        assertTrue(search.matches("clothes/pantyhose", "パンスト"))
        assertTrue(search.matches("パンスト", "pantyhose"))
    }

    @Test
    fun `a tag in no group matches only on its own name`() {
        assertFalse(search.matches("タイツ", "パンスト"))
    }
}
