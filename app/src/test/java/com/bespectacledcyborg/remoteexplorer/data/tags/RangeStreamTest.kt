package com.bespectacledcyborg.remoteexplorer.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chunking between "read at an offset" and "here is a stream".
 *
 * Worth its own tests because the bug it can have is quiet: a range that reads one byte too
 * few loses the last record of every resume, and one that reads too many picks up records
 * the next pass will then skip, since that pass starts from the length this one was told.
 * Either way the index ends up missing galleries and nothing reports an error.
 */
class RangeStreamTest {

    private val content = "0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()

    private fun stream(from: Long, until: Long, onClose: () -> Unit = {}) =
        RangeStream(from, until, ::readAt, onClose)

    private fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= content.size) return -1
        val take = minOf(size, content.size - position.toInt())
        content.copyInto(buffer, offset, position.toInt(), position.toInt() + take)
        return take
    }

    @Test
    fun `serves exactly the range`() {
        assertEquals("456789", stream(4, 10).readBytes().decodeToString())
    }

    @Test
    fun `starts at zero`() {
        assertEquals("0123", stream(0, 4).readBytes().decodeToString())
    }

    @Test
    fun `runs to the end`() {
        assertEquals("xyz", stream(33, content.size.toLong()).readBytes().decodeToString())
    }

    @Test
    fun `an empty range reads nothing`() {
        assertEquals("", stream(5, 5).readBytes().decodeToString())
        assertEquals(-1, stream(5, 5).read())
    }

    @Test
    fun `single byte reads agree with bulk reads`() {
        val one = stream(4, 10)
        val bytes = generateSequence { one.read().takeIf { it != -1 } }
            .map { it.toByte() }
            .toList()
            .toByteArray()

        assertEquals("456789", bytes.decodeToString())
    }

    /**
     * A reader that answers short — which SMB is entitled to do — must not be read as the
     * end of the range.
     */
    @Test
    fun `survives a reader that answers in dribs`() {
        val dribbling = RangeStream(0, content.size.toLong(), { position, buffer, offset, size ->
            readAt(position, buffer, offset, minOf(size, 3))
        }, {})

        assertEquals(content.decodeToString(), dribbling.readBytes().decodeToString())
    }

    @Test
    fun `closing the stream closes the reader under it`() {
        var closed = false
        stream(0, 4) { closed = true }.use { it.readBytes() }

        assertTrue("a leaked handle per index read exhausts the share's supply", closed)
    }
}
