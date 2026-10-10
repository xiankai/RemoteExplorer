package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.tags.NARRATION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnnotationDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var annotations: AnnotationDao
    private lateinit var tags: LocalDatabaseDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
        annotations = db.annotationDao()
        tags = db.localDatabaseDao()
    }

    @After
    fun tearDown() = db.close()

    private fun update(path: String, id: String, text: String?) =
        AnnotationUpdate(path, id, text, language = "en", derivedFrom = null, supersedes = null)

    private fun texts(path: String) = annotations.getAnnotations(1, path).map { it.text }

    @Test
    fun `the last record for a path and id wins, and a tombstone removes it`() {
        annotations.apply(
            1, replace = false,
            updates = listOf(update("g/a", "p", "first"), update("g/a", "p", "second"), update("g/b", "p", "kept")),
            generation = "1", bytesRead = 10,
        )
        assertEquals(listOf("second"), texts("g/a"))

        annotations.apply(1, replace = false, updates = listOf(update("g/a", "p", null)), generation = "1", bytesRead = 20)

        assertEquals(emptyList<String>(), texts("g/a"))
        assertEquals(listOf("kept"), texts("g/b"))
        assertEquals(AnnotationIngest(1, "1", 20), annotations.getIngest(1))
    }

    @Test
    fun `a full pull replaces what the connection had`() {
        annotations.apply(1, replace = false, updates = listOf(update("g/a", "p", "old")), generation = "1", bytesRead = 10)

        annotations.apply(1, replace = true, updates = listOf(update("g/b", "p", "new")), generation = "2", bytesRead = 5)

        assertEquals(emptyList<String>(), texts("g/a"))
        assertEquals(listOf("new"), texts("g/b"))
    }

    @Test
    fun `the index says which galleries have narration, and a later record takes it back`() {
        fun apply(vararg updates: DirectoryTagUpdate, replace: Boolean = false) =
            tags.applyTagIndex(1, replace, updates.toList(), emptyList(), "1", 0)

        apply(
            DirectoryTagUpdate("g/a", listOf("t"), deleted = false, annotations = listOf(NARRATION)),
            DirectoryTagUpdate("g/b", listOf("t"), deleted = false, annotations = listOf(NARRATION)),
            DirectoryTagUpdate("g/c", listOf("t"), deleted = false),
        )
        assertEquals(
            setOf("g/a", "g/b"),
            tags.getAnnotatedDirectories(NARRATION).map { it.parentPath }.toSet(),
        )

        apply(
            DirectoryTagUpdate("g/a", listOf("t"), deleted = false),
            DirectoryTagUpdate("g/b", listOf("t"), deleted = true, annotations = listOf(NARRATION)),
        )
        assertEquals(emptyList<AnnotatedDirectory>(), tags.getAnnotatedDirectories(NARRATION))

        apply(DirectoryTagUpdate("g/c", listOf("t"), deleted = false, annotations = listOf(NARRATION)))
        apply(DirectoryTagUpdate("g/d", listOf("t"), deleted = false, annotations = listOf(NARRATION)), replace = true)
        assertEquals(listOf(AnnotatedDirectory(1, "g/d")), tags.getAnnotatedDirectories(NARRATION))
    }
}
