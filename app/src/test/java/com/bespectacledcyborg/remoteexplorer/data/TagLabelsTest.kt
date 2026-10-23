package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class TagLabelsTest {

    @Test
    fun `a group's root is left off the label`() {
        val tags = listOf(
            LocalTagWithMetadata("pantyhose", 3, canonical = "clothes/pantyhose"),
            LocalTagWithMetadata("Tiger", 1, canonical = "characters/azur lane/Tiger"),
        )
        assertEquals(
            mapOf("pantyhose" to "pantyhose", "Tiger" to "azur lane/Tiger"),
            displayLabels(tags),
        )
    }

    @Test
    fun `labels that would read the same keep their roots`() {
        val tags = listOf(
            LocalTagWithMetadata("twintails", 3, canonical = "female/twintails"),
            LocalTagWithMetadata("male:twintails", 1, canonical = "male/twintails"),
            LocalTagWithMetadata("bunny", 1, canonical = "clothes/bunny"),
        )
        assertEquals(
            mapOf(
                "twintails" to "female/twintails",
                "male:twintails" to "male/twintails",
                "bunny" to "bunny",
            ),
            displayLabels(tags),
        )
    }

    @Test
    fun `a tag outside any group, or still waiting for a root, is shown as it is`() {
        val tags = listOf(
            LocalTagWithMetadata("artist:someone", 1),
            LocalTagWithMetadata("1girl", 4, canonical = "?/1girl"),
        )
        assertEquals(
            mapOf("artist:someone" to "artist:someone", "1girl" to "1girl"),
            displayLabels(tags),
        )
    }

    @Test
    fun `a grouped tag whose short name matches a bare tag keeps its root`() {
        val tags = listOf(
            LocalTagWithMetadata("blush", 1),
            LocalTagWithMetadata("blush face", 2, canonical = "expression/blush"),
        )
        assertEquals(
            mapOf("blush" to "blush", "blush face" to "expression/blush"),
            displayLabels(tags),
        )
    }
}
