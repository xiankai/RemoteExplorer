package com.bespectacledcyborg.remoteexplorer.data.tags

import LocalDatabaseDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase
import com.bespectacledcyborg.remoteexplorer.data.db.CredentialConverters
import com.bespectacledcyborg.remoteexplorer.data.db.LocalDatabaseDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * When the alias layer is taken, and what a pass leaves behind — against a real database,
 * because "replaced" and "left alone" are only visible in the rows.
 */
@RunWith(RobolectricTestRunner::class)
class AliasFileTagSourceTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LocalDatabaseDao
    private lateinit var files: FakeFiles
    private lateinit var source: AliasFileTagSource

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
        dao = db.localDatabaseDao()
        files = FakeFiles()
        source = AliasFileTagSource(files, LocalDatabaseDataSource(dao, Dispatchers.Unconfined))
        dao.applyDirectoryTags(1, "gallery-dl/pixiv/1", listOf("眼鏡"), deleted = false)
        dao.applyDirectoryTags(1, "gallery-dl/examplebooru/2", listOf("female:glasses"), deleted = false)
    }

    @After
    fun tearDown() = db.close()

    private fun meta(generation: Int) =
        """{"schema":1,"generation":$generation,"groups":1,"queued":0,"overrides":0,""" +
                """"built_at":"2026-09-26T12:42:49+0900"}"""

    private val glassesGroup =
        """{"canonical": "female/glasses", "confidence": "medium", "members": """ +
                """["glasses", "female:glasses", "眼鏡"], "needs_root": true, """ +
                """"sources": ["danbooru"]}"""

    private fun widened(tag: String) = dao.getDirectoriesWithTag(tag).map { it.parentPath }

    @Test
    fun `takes the alias file on the first pass`() = runTest {
        files.meta = meta(7)
        files.aliases = glassesGroup + "\n"

        val progress = source.index(1, reindexAll = false, limit = 50)

        assertEquals(1, progress.indexed)
        assertEquals(listOf("gallery-dl/examplebooru/2", "gallery-dl/pixiv/1"), widened("眼鏡"))
        assertEquals("7", dao.getTagAliasGeneration(1))
    }

    /** Hidden tags ride on the meta and are replaced with the groups, both ways. */
    @Test
    fun `hidden tags in the meta leave the tag list, and come back when unhidden`() = runTest {
        dao.applyDirectoryTags(1, "gallery-dl/pixiv/3", listOf("1girl"), deleted = false)
        files.meta = meta(7).dropLast(1) + ""","hidden":["1girl"]}"""
        files.aliases = glassesGroup
        source.index(1, reindexAll = false, limit = 50)
        assertEquals(listOf("female/glasses"), dao.getTags().map { it.label })

        files.meta = meta(8)
        source.index(1, reindexAll = false, limit = 50)
        assertEquals(
            listOf("1girl", "female/glasses"),
            dao.getTags().map { it.label }.sorted(),
        )
    }

    @Test
    fun `does nothing while the generation is unchanged`() = runTest {
        files.meta = meta(7)
        files.aliases = glassesGroup
        source.index(1, reindexAll = false, limit = 50)
        files.aliasReads = 0

        val progress = source.index(1, reindexAll = false, limit = 50)

        assertEquals(TagIndexProgress.NOTHING, progress)
        assertEquals("the body is not fetched just to find it unchanged", 0, files.aliasReads)
    }

    @Test
    fun `a new generation replaces what was there`() = runTest {
        files.meta = meta(7)
        files.aliases = glassesGroup
        source.index(1, reindexAll = false, limit = 50)

        files.meta = meta(8)
        files.aliases = ""
        source.index(1, reindexAll = false, limit = 50)

        assertEquals(listOf("gallery-dl/pixiv/1"), widened("眼鏡"))
        assertEquals("8", dao.getTagAliasGeneration(1))
    }

    @Test
    fun `a forced refresh takes it again regardless`() = runTest {
        files.meta = meta(7)
        files.aliases = glassesGroup
        source.index(1, reindexAll = false, limit = 50)
        files.aliasReads = 0

        source.index(1, reindexAll = true, limit = 50)

        assertEquals(1, files.aliasReads)
    }

    @Test
    fun `a share with no alias file is left alone`() = runTest {
        assertEquals(TagIndexProgress.NOTHING, source.index(1, reindexAll = false, limit = 50))
        assertNull(dao.getTagAliasGeneration(1))
    }

    /**
     * A meta with no body beside it is a build caught half way, or a hand-deleted file.
     * Emptying the groups over it would narrow every lookup for nothing.
     */
    @Test
    fun `a meta with no body keeps the groups it had`() = runTest {
        files.meta = meta(7)
        files.aliases = glassesGroup
        source.index(1, reindexAll = false, limit = 50)

        files.meta = meta(8)
        files.aliases = null
        source.index(1, reindexAll = false, limit = 50)

        assertEquals(2, widened("眼鏡").size)
        assertEquals("7", dao.getTagAliasGeneration(1))
    }

    @Test
    fun `an unreadable meta reads as no alias file`() = runTest {
        files.meta = "{\"schema\":1"
        files.aliases = glassesGroup

        assertEquals(TagIndexProgress.NOTHING, source.index(1, reindexAll = false, limit = 50))
        assertEquals(1, widened("眼鏡").size)
    }

    /**
     * Lines but no usable group is a format this build cannot read, not an emptied mapping.
     * Keep the old groups and leave the generation unrecorded, so a later pass retries.
     */
    @Test
    fun `a file with no readable group keeps the old ones`() = runTest {
        files.meta = meta(7)
        files.aliases = glassesGroup
        source.index(1, reindexAll = false, limit = 50)

        files.meta = meta(8)
        files.aliases = "{\"groups_v2\":[]}\n{broken\n"
        assertEquals(TagIndexProgress.NOTHING, source.index(1, reindexAll = false, limit = 50))

        assertEquals(2, widened("眼鏡").size)
        assertEquals("7", dao.getTagAliasGeneration(1))
    }

    /** One bad line costs that group, not the file. */
    @Test
    fun `skips a bad line and keeps the rest`() = runTest {
        files.meta = meta(7)
        files.aliases = "{broken\n$glassesGroup\n"

        assertEquals(1, source.index(1, reindexAll = false, limit = 50).indexed)
        assertEquals(2, widened("眼鏡").size)
    }

    private class FakeFiles : TagIndexFiles {
        var meta: String? = null
        var aliases: String? = null
        var aliasReads = 0

        override suspend fun readWhole(connectionId: Int, path: String): ByteArray? =
            if (path == TagIndex.ALIASES_META) meta?.toByteArray() else null

        override suspend fun openRange(
            connectionId: Int,
            path: String,
            from: Long,
            to: Long,
        ): InputStream? = null

        override suspend fun openWhole(connectionId: Int, path: String): InputStream? {
            if (path != TagIndex.ALIASES) return null
            val body = aliases ?: return null
            aliasReads++
            return ByteArrayInputStream(body.toByteArray())
        }
    }
}
