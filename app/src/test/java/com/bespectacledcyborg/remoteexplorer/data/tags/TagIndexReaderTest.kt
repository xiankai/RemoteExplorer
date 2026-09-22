package com.bespectacledcyborg.remoteexplorer.data.tags

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream

/**
 * How much of the index a pass decides to fetch, and from where.
 *
 * Robolectric only for `android.util.Log`; nothing here touches a device. The share is a
 * fake, because the interesting part is the decision and not smbj.
 */
@RunWith(RobolectricTestRunner::class)
class TagIndexReaderTest {

    private val records = listOf(
        """{"scope":"dir","path":"g/one","tags":["a"],"files":2}""",
        """{"scope":"dir","path":"g/two","tags":["b","c"],"files":3}""",
        """{"scope":"file","path":"g/three/x.jpg","tags":["d"]}""",
    )
    private val log = records.joinToString("\n", postfix = "\n").toByteArray()

    private fun meta(generation: String = "g1", bytes: Long = log.size.toLong()) =
        """{"schema":1,"generation":"$generation","records":3,"bytes":$bytes}""".toByteArray()

    @Test
    fun `a share with no index has nothing to do`() = runTest {
        val reader = TagIndexReader(FakeFiles())

        assertEquals(TagIndexPlan.NothingToDo, reader.plan(1, state = null))
    }

    @Test
    fun `an empty log has nothing to do`() = runTest {
        val reader = TagIndexReader(FakeFiles(meta = meta(bytes = 0)))

        assertEquals(TagIndexPlan.NothingToDo, reader.plan(1, state = null))
    }

    @Test
    fun `a first pass takes everything`() = runTest {
        val reader = TagIndexReader(FakeFiles(meta = meta(), log = log))

        val plan = reader.plan(1, state = null)

        assertTrue(plan is TagIndexPlan.FullPull)
    }

    @Test
    fun `nothing appended since last time is nothing to do`() = runTest {
        val reader = TagIndexReader(FakeFiles(meta = meta(), log = log))

        val plan = reader.plan(1, TagIngestState("g1", log.size.toLong()))

        assertEquals(TagIndexPlan.NothingToDo, plan)
    }

    @Test
    fun `more log than was read resumes from the offset`() = runTest {
        val reader = TagIndexReader(FakeFiles(meta = meta(), log = log))

        val plan = reader.plan(1, TagIngestState("g1", 10L))

        assertEquals(TagIndexPlan.Resume(TagIndexMeta(1, "g1", 3, log.size.toLong()), 10L), plan)
    }

    /**
     * A rebuild renumbers everything, so the stored offset points into a file that no longer
     * exists in that form. Resuming there would splice the tail of a new log onto the head of
     * an old one and call it an index.
     */
    @Test
    fun `a new generation re-pulls from scratch`() = runTest {
        val reader = TagIndexReader(FakeFiles(meta = meta(generation = "g2"), log = log))

        val plan = reader.plan(1, TagIngestState("g1", 10L))

        assertTrue(plan is TagIndexPlan.FullPull)
    }

    /**
     * Not supposed to happen — the log only grows within a generation — so the question is
     * only which way to fail. Re-pulling costs one transfer; trusting the offset reads from
     * past the end and ingests nothing, forever, without ever saying so.
     */
    @Test
    fun `an offset past the end re-pulls rather than sticking`() = runTest {
        val reader = TagIndexReader(FakeFiles(meta = meta(), log = log))

        val plan = reader.plan(1, TagIngestState("g1", log.size + 500L))

        assertTrue(plan is TagIndexPlan.FullPull)
    }

    @Test
    fun `a full pull prefers the compressed copy`() = runTest {
        val files = FakeFiles(meta = meta(), log = log, compressed = gzip(log))
        val reader = TagIndexReader(files)

        val seen = mutableListOf<TagIndexRecord>()
        reader.read(1, reader.plan(1, state = null), seen::add)

        assertEquals(listOf("g/one", "g/two", "g/three/x.jpg"), seen.map { it.path })
        assertEquals("the plain log should not have been touched", 0, files.rangeReads)
    }

    /**
     * The compressed copy is an optimisation. A generator that has not written it yet must
     * not mean no tags at all.
     */
    @Test
    fun `a full pull falls back to the plain log`() = runTest {
        val reader = TagIndexReader(FakeFiles(meta = meta(), log = log, compressed = null))

        val seen = mutableListOf<TagIndexRecord>()
        reader.read(1, reader.plan(1, state = null), seen::add)

        assertEquals(listOf("g/one", "g/two", "g/three/x.jpg"), seen.map { it.path })
    }

    @Test
    fun `a resume reads only what was appended`() = runTest {
        val head = (records[0] + "\n").toByteArray()
        val files = FakeFiles(meta = meta(), log = log, compressed = gzip(log))
        val reader = TagIndexReader(files)

        val seen = mutableListOf<TagIndexRecord>()
        reader.read(1, reader.plan(1, TagIngestState("g1", head.size.toLong())), seen::add)

        // The first record was already ingested last time, so it must not arrive again —
        // the whole point of storing a byte offset rather than re-reading and de-duping.
        assertEquals(listOf("g/two", "g/three/x.jpg"), seen.map { it.path })
        assertEquals(head.size.toLong(), files.lastRangeFrom)
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
        }.toByteArray()

    /** The share, with only the three files this reader ever asks for. */
    private class FakeFiles(
        private val meta: ByteArray? = null,
        private val log: ByteArray? = null,
        private val compressed: ByteArray? = null,
    ) : TagIndexFiles {
        var rangeReads = 0
        var lastRangeFrom = -1L

        override suspend fun readWhole(connectionId: Int, path: String): ByteArray? =
            if (path == TagIndex.META) meta else null

        override suspend fun openRange(
            connectionId: Int,
            path: String,
            from: Long,
            to: Long,
        ): InputStream? {
            if (path != TagIndex.LOG) return null
            val bytes = log ?: return null
            rangeReads++
            lastRangeFrom = from
            return ByteArrayInputStream(bytes, from.toInt(), (to - from).toInt())
        }

        override suspend fun openCompressed(connectionId: Int, path: String): InputStream? =
            compressed?.let { ByteArrayInputStream(it) }
    }
}
