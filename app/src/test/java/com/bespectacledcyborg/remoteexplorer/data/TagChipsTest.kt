package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class TagChipsTest {

    private fun tag(tag: String, canonical: String? = null) = LocalTagWithMetadata(tag, 0, canonical)

    @Test
    fun `a listed group opens and reads as the tag list's own entry`() {
        val listed = listedChips(
            listOf(
                tag("character:hagoromo gitsune", "characters/hagoromo gitsune"),
                tag("Original"),
            )
        )

        val chips = tagChips(
            listOf(tag("羽衣狐", "characters/hagoromo gitsune"), tag("Original")),
            listed,
        )

        assertEquals(
            listOf(
                TagChip("character:hagoromo gitsune", "hagoromo gitsune"),
                TagChip("Original", "Original"),
            ),
            chips,
        )
    }

    @Test
    fun `a tag missing from the list, like a hidden one, gets the same root-stripped label`() {
        val chips = tagChips(listOf(tag("x", "?/clothes/pantyhose"), tag("plain")), emptyMap())

        assertEquals(
            listOf(TagChip("x", "pantyhose"), TagChip("plain", "plain")),
            chips,
        )
    }

    @Test
    fun `two members of one group make one chip`() {
        val chips = tagChips(
            listOf(tag("眼鏡", "female/glasses"), tag("female:glasses", "female/glasses")),
            emptyMap(),
        )

        assertEquals(listOf(TagChip("眼鏡", "glasses")), chips)
    }

    @Test
    fun `a raw tag spelling a canonical is not taken for that group`() {
        val listed = listedChips(listOf(tag("a", "x/ab"), tag("b", "y/ab")))

        val chips = tagChips(listOf(tag("x/ab")), listed)

        assertEquals(listOf(TagChip("x/ab", "ab")), chips)
    }
}
