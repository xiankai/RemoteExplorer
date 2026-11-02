package com.bespectacledcyborg.remoteexplorer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun `digit runs order by value`() {
        assertEquals(listOf("1", "2", "10", "100", "101"), sorted("1", "10", "100", "101", "2"))
    }

    @Test
    fun `descending is the reverse`() {
        assertEquals(
            listOf("101", "100", "10", "2", "1"),
            listOf("1", "10", "100", "101", "2").sortedWith(NaturalOrder.reversed()),
        )
    }

    @Test
    fun `numbers inside text order by value`() {
        assertEquals(
            listOf("page 2.jpg", "page 9.jpg", "page 10.jpg", "page 10b.jpg", "page 11.jpg"),
            sorted("page 10.jpg", "page 11.jpg", "page 9.jpg", "page 10b.jpg", "page 2.jpg"),
        )
        assertEquals(listOf("v1.2.10", "v1.10.1", "v2.0"), sorted("v2.0", "v1.10.1", "v1.2.10"))
    }

    @Test
    fun `leading zeros do not change the value`() {
        assertEquals(listOf("001", "2", "010", "11"), sorted("11", "010", "2", "001"))
    }

    @Test
    fun `equal values with different padding sort the same way every time`() {
        assertEquals(listOf("001", "01", "1"), sorted("1", "001", "01"))
        assertEquals(listOf("001", "01", "1"), sorted("01", "1", "001"))
    }

    @Test
    fun `digit runs too long for a Long still order by value`() {
        val big = "123456789012345678901234567890"
        val bigger = "923456789012345678901234567890"
        val longer = "1$big"
        assertEquals(
            listOf("id $big", "id $bigger", "id $longer"),
            sorted("id $longer", "id $bigger", "id $big"),
        )
    }

    @Test
    fun `case is ignored, then breaks the tie`() {
        assertEquals(
            listOf("Apple", "apple", "banana", "Cherry"),
            sorted("Cherry", "banana", "apple", "Apple"),
        )
    }

    @Test
    fun `a prefix sorts first`() {
        assertEquals(listOf("a", "a1", "a1b"), sorted("a1b", "a1", "a"))
    }

    @Test
    fun `digits sort after punctuation and ahead of letters`() {
        assertEquals(listOf("a-", "a1", "ab"), sorted("ab", "a1", "a-"))
    }

    @Test
    fun `only identical names compare equal`() {
        assertEquals(0, NaturalOrder.compare("x10", "x10"))
        assertTrue(NaturalOrder.compare("x10", "x010") != 0)
    }
}
