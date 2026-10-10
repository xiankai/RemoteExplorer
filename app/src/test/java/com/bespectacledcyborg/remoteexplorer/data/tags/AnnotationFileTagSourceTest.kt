package com.bespectacledcyborg.remoteexplorer.data.tags

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase
import com.bespectacledcyborg.remoteexplorer.data.db.CredentialConverters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class AnnotationFileTagSourceTest {

    private lateinit var db: AppDatabase
    private val files = Share()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private val source by lazy {
        AnnotationFileTagSource(
            IndexLogReader(files, IndexLog.ANNOTATIONS),
            db.annotationDao(),
            Dispatchers.Unconfined,
        )
    }

    private fun narration(path: String, text: String) =
        """{"path":"$path","scope":"dir","id":"gallery-dl:pixiv.caption","kind":"narration",""" +
                """"quality":"narration","body":{"value":"$text","language":"en"}}"""

    private fun tombstone(path: String) =
        """{"path":"$path","scope":"dir","id":"gallery-dl:pixiv.caption","kind":"narration",""" +
                """"quality":"not","deleted":true}"""

    private fun texts(path: String) = db.annotationDao().getAnnotations(1, path).map { it.text }

    @Test
    fun `a first pass takes the store and a later one only what was appended`() = runTest {
        files.write(narration("g/a", "one"), narration("g/b", "two"))
        assertEquals(2, source.index(1, reindexAll = false, limit = 0).indexed)
        assertEquals(listOf("one"), texts("g/a"))

        files.append(tombstone("g/a"))
        assertEquals(1, source.index(1, reindexAll = false, limit = 0).indexed)

        assertEquals(emptyList<String>(), texts("g/a"))
        assertEquals(listOf("two"), texts("g/b"))
        assertEquals(TagIndexProgress.NOTHING, source.index(1, reindexAll = false, limit = 0))
    }

    @Test
    fun `a share without the store has nothing to do`() = runTest {
        assertEquals(TagIndexProgress.NOTHING, source.index(1, reindexAll = false, limit = 0))
    }

    private class Share : TagIndexFiles {
        private var log = ByteArray(0)

        fun write(vararg lines: String) {
            log = lines.joinToString("\n", postfix = "\n").toByteArray()
        }

        fun append(vararg lines: String) {
            log += lines.joinToString("\n", postfix = "\n").toByteArray()
        }

        override suspend fun readWhole(connectionId: Int, path: String): ByteArray? =
            if (path != TagIndex.ANNOTATIONS_META || log.isEmpty()) null
            else """{"schema":1,"generation":1,"records":0,"bytes":${log.size}}""".toByteArray()

        override suspend fun openRange(connectionId: Int, path: String, from: Long, to: Long): InputStream? =
            if (path != TagIndex.ANNOTATIONS) null
            else ByteArrayInputStream(log, from.toInt(), (to - from).toInt())

        override suspend fun openWhole(connectionId: Int, path: String): InputStream? = null
    }
}
