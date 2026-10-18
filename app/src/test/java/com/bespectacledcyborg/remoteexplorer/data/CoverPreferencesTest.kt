package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.data.db.CoverShuffle
import com.bespectacledcyborg.remoteexplorer.data.db.FolderCoverChoice
import com.bespectacledcyborg.remoteexplorer.data.db.FolderCoverChoiceDao
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPreferencesTest {

    private class Choices : FolderCoverChoiceDao {
        val rows = mutableMapOf<Pair<Int, String>, FolderCoverChoice>()
        override suspend fun get(connectionId: Int, path: String) = rows[connectionId to path]
        override suspend fun set(choice: FolderCoverChoice) {
            rows[choice.connectionId to choice.path] = choice
        }
        override suspend fun clear(connectionId: Int, path: String) {
            rows.remove(connectionId to path)
        }
    }

    private val choices = Choices()
    private var shuffle = CoverShuffle.MARKED
    private var pairings = Pairings.NONE
    private val tags = mutableMapOf<Pair<Int, String>, List<String>>()
    private val aliases = mutableMapOf<String, String>()
    private var seeds = generateSequence(100L) { it + 1 }.iterator()

    private val preferences = CoverPreferences(
        choices = choices,
        shuffleSetting = { shuffle },
        pairings = { pairings },
        directoryTags = { id, path -> tags[id to path].orEmpty() },
        aliasCanonicals = { _, list -> list.mapNotNull { aliases[it] } },
        newSeed = { seeds.next() },
    )

    private suspend fun cover(path: String, connectionId: Int = 1, sorted: CoverOrder = CoverOrder.Name) =
        preferences.coverFor(connectionId, path, sorted, visitSeed = 7)

    private suspend fun order(path: String, connectionId: Int = 1) = cover(path, connectionId).order

    private suspend fun applied(path: String) = cover(path).applied

    @Test
    fun `a folder left on default follows the screen's sort`() = runTest {
        assertEquals(CoverOrder.Name, order("gallery-dl/pixiv"))
    }

    @Test
    fun `newest, shuffle and shuffle every visit each give their own order`() = runTest {
        preferences.choose(1, "a", CoverMode.NEWEST)
        preferences.choose(1, "b", CoverMode.SHUFFLE)
        preferences.choose(1, "c", CoverMode.SHUFFLE_EVERY_VISIT)

        assertEquals(CoverOrder.Newest, order("a"))
        assertEquals(CoverOrder.Shuffled(101), order("b"))
        assertEquals(CoverOrder.Shuffled(7), order("c"))
    }

    @Test
    fun `choosing shuffle again rerolls, and default forgets the choice`() = runTest {
        preferences.choose(1, "b", CoverMode.SHUFFLE)
        val first = order("b")
        preferences.choose(1, "b", CoverMode.SHUFFLE)
        assertNotEquals(first, order("b"))

        preferences.choose(1, "b", CoverMode.DEFAULT)
        assertEquals(CoverOrder.Name, order("b"))
        assertTrue(choices.rows.isEmpty())
    }

    @Test
    fun `the global switch shuffles folders left on default, or turns every shuffle off`() = runTest {
        preferences.choose(1, "kept", CoverMode.SHUFFLE)
        preferences.choose(1, "newest", CoverMode.NEWEST)

        shuffle = CoverShuffle.ALL
        assertEquals(CoverOrder.Shuffled(7), order("left-on-default"))
        assertEquals(CoverOrder.Shuffled(100), order("kept"))
        assertEquals(CoverOrder.Newest, order("newest"))

        shuffle = CoverShuffle.OFF
        assertEquals(CoverOrder.Name, order("left-on-default"))
        assertEquals(CoverOrder.Name, order("kept"))
        assertEquals(CoverOrder.Newest, order("newest"))
        assertEquals(CoverMode.SHUFFLE, preferences.choiceFor(1, "kept"))
    }

    @Test
    fun `the applied mode names what the cover follows, not what was stored`() = runTest {
        preferences.choose(1, "newest", CoverMode.NEWEST)
        preferences.choose(1, "kept", CoverMode.SHUFFLE)
        preferences.choose(1, "every", CoverMode.SHUFFLE_EVERY_VISIT)
        preferences.choose(1, "gallery-dl/examplebooru/[1] work", CoverMode.NEWEST)

        assertEquals(CoverMode.NEWEST, applied("newest"))
        assertEquals(CoverMode.SHUFFLE, applied("kept"))
        assertEquals(CoverMode.SHUFFLE_EVERY_VISIT, applied("every"))
        assertEquals(CoverMode.DEFAULT, applied("left-on-default"))
        assertEquals(CoverMode.DEFAULT, applied("gallery-dl/examplebooru/[1] work"))

        shuffle = CoverShuffle.ALL
        assertEquals(CoverMode.SHUFFLE_EVERY_VISIT, applied("left-on-default"))
        assertEquals(CoverMode.SHUFFLE, applied("kept"))

        shuffle = CoverShuffle.OFF
        assertEquals(CoverMode.DEFAULT, applied("kept"))
        assertEquals(CoverMode.DEFAULT, applied("every"))
        assertEquals(CoverMode.NEWEST, applied("newest"))
    }

    @Test
    fun `a folder left on default under a newest-first screen is not marked newest`() = runTest {
        val chosen = cover("left-on-default", sorted = CoverOrder.Newest)
        assertEquals(CoverOrder.Newest, chosen.order)
        assertEquals(CoverMode.DEFAULT, chosen.applied)
    }

    @Test
    fun `anything under examplebooru is never shuffled, samplebooru is`() = runTest {
        shuffle = CoverShuffle.ALL
        preferences.choose(1, "gallery-dl/examplebooru/[123] work", CoverMode.SHUFFLE)

        assertEquals(CoverOrder.Name, order("gallery-dl/examplebooru"))
        assertEquals(CoverOrder.Name, order("gallery-dl/examplebooru/[123] work"))
        assertEquals(CoverOrder.Shuffled(7), order("gallery-dl/samplebooru/[456] other"))
        assertEquals(CoverOrder.Shuffled(7), order("gallery-dl/examplebooru-mirror"))
    }

    @Test
    fun `a gallery the index tags as manga or doujinshi keeps its first page`() = runTest {
        shuffle = CoverShuffle.ALL
        tags[1 to "g/plain"] = listOf("manga")
        tags[1 to "g/namespaced"] = listOf("female:glasses", "media/Manga")
        tags[1 to "g/category"] = listOf("category:doujinshi")
        tags[1 to "g/aliased"] = listOf("同人誌")
        aliases["同人誌"] = "media/doujinshi"
        tags[1 to "g/mangaka"] = listOf("mangaka", "artist:manga-kun")
        tags[1 to "g/untagged-neighbour"] = listOf("illustration")

        listOf("g/plain", "g/namespaced", "g/category", "g/aliased").forEach {
            assertTrue(it, preferences.keepsFirstPage(1, it))
            assertEquals(it, CoverOrder.Name, order(it))
        }
        assertFalse(preferences.keepsFirstPage(1, "g/mangaka"))
        assertFalse(preferences.keepsFirstPage(1, "g/untagged-neighbour"))
    }

    @Test
    fun `every folder beneath a manga folder keeps its first page too`() = runTest {
        shuffle = CoverShuffle.ALL
        tags[1 to "g/series"] = listOf("manga")
        preferences.choose(1, "g/series/vol 1", CoverMode.SHUFFLE)

        listOf("g/series/vol 1", "g/series/vol 1/chapter 3", "/g/series/vol 2/").forEach {
            assertTrue(it, preferences.keepsFirstPage(1, it))
            assertEquals(it, CoverOrder.Name, order(it))
            assertEquals(it, CoverMode.DEFAULT, applied(it))
        }
        assertFalse(preferences.keepsFirstPage(1, "g"))
        assertFalse(preferences.keepsFirstPage(1, "g/other/vol 1"))
        assertFalse(preferences.keepsFirstPage(1, "g/series-extra/vol 1"))
    }

    @Test
    fun `a manga folder tagged later still reaches folders already asked about`() = runTest {
        assertFalse(preferences.keepsFirstPage(1, "g/series/vol 1"))

        tags[1 to "g/series"] = listOf("manga")

        assertTrue(preferences.keepsFirstPage(1, "g/series/vol 1"))
    }

    @Test
    fun `a paired local copy inherits manga from its share's ancestor`() = runTest {
        pairings = Pairings(listOf(Pairing(2, "", 1, "")))
        tags[1 to "gallery-dl/pixiv/[9] manga work"] = listOf("manga")

        assertTrue(preferences.keepsFirstPage(2, "gallery-dl/pixiv/[9] manga work/part 2"))
    }

    @Test
    fun `a paired local copy shares its share's choice and its manga tags`() = runTest {
        pairings = Pairings(listOf(Pairing(2, "", 1, "")))
        tags[1 to "gallery-dl/pixiv/[9] manga work"] = listOf("manga")

        preferences.choose(2, "gallery-dl/pixiv", CoverMode.NEWEST)

        assertEquals(CoverMode.NEWEST, preferences.choiceFor(1, "gallery-dl/pixiv"))
        assertEquals(CoverOrder.Newest, order("gallery-dl/pixiv", connectionId = 2))
        assertTrue(preferences.keepsFirstPage(2, "gallery-dl/pixiv/[9] manga work"))
    }

    @Test
    fun `manga tags are matched on their last segment only`() {
        assertTrue(isMangaTag("Doujinshi"))
        assertTrue(isMangaTag("media:manga"))
        assertTrue(isMangaTag("type/manga "))
        assertFalse(isMangaTag("manga/artist"))
        assertFalse(isMangaTag("mangaka"))
        assertTrue(isMangaPath("/gallery-dl/examplebooru/"))
        assertFalse(isMangaPath("gallery-dl/exampleboorux"))
    }
}
