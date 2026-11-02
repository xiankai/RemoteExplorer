package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagsOnDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LocalDatabaseDao

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
    }

    @After
    fun tearDown() = db.close()

    private fun tagsOn(connectionId: Int, directory: String, filename: String? = null) =
        dao.getTagsOn(connectionId, directory, filename).map { it.tag to it.canonical }

    @Test
    fun `a folder lists its own directory tags and nothing from its files or neighbours`() {
        dao.addDirTagRelation(DirTagRelation(1, "g/one", "b"))
        dao.addDirTagRelation(DirTagRelation(1, "g/one", "a"))
        dao.addDirTagRelation(DirTagRelation(1, "g/two", "c"))
        dao.addDirTagRelation(DirTagRelation(2, "g/one", "d"))
        dao.addFileTagRelation(FileTagRelation(1, "g/one", "x.jpg", "e"))

        assertEquals(listOf("a" to null, "b" to null), tagsOn(1, "g/one"))
    }

    @Test
    fun `a file lists its own tags and its gallery's, each once`() {
        dao.addDirTagRelation(DirTagRelation(1, "g/one", "shared"))
        dao.addDirTagRelation(DirTagRelation(1, "g/one", "gallery"))
        dao.addFileTagRelation(FileTagRelation(1, "g/one", "x.jpg", "shared"))
        dao.addFileTagRelation(FileTagRelation(1, "g/one", "x.jpg", "own"))
        dao.addFileTagRelation(FileTagRelation(1, "g/one", "y.jpg", "other"))

        assertEquals(
            listOf("gallery" to null, "own" to null, "shared" to null),
            tagsOn(1, "g/one", "x.jpg"),
        )
    }

    @Test
    fun `each tag carries its alias group, and hidden tags are still listed`() {
        dao.addDirTagRelation(DirTagRelation(1, "g/one", "羽衣狐"))
        dao.addDirTagRelation(DirTagRelation(1, "g/one", "boring"))
        dao.replaceTagAliases(
            1,
            listOf(TagAlias(1, "羽衣狐", "characters/hagoromo gitsune")),
            "1",
            hidden = listOf("boring"),
        )

        assertEquals(
            listOf("boring" to null, "羽衣狐" to "characters/hagoromo gitsune"),
            tagsOn(1, "g/one"),
        )
    }
}
