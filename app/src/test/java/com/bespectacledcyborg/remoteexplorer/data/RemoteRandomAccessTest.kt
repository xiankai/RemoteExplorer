package com.bespectacledcyborg.remoteexplorer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Both video decoders read through [RemoteRandomAccess], and neither can say why a read
 * failed — so the reader's answers at the edges of the file, and what it remembers about a
 * failure, are the whole contract. Driven through a [SourceFile] over a byte array, which is
 * the seam that lets it run with no share at all.
 */
class RemoteRandomAccessTest {

    private val bytes = ByteArray(100) { it.toByte() }

    @Test
    fun `size is the file's real length`() {
        RemoteRandomAccess { ByteSourceFile(bytes) }.use { reader ->
            assertEquals(100L, reader.size())
        }
    }

    @Test
    fun `reads at an offset rather than from the start`() {
        RemoteRandomAccess { ByteSourceFile(bytes) }.use { reader ->
            val buffer = ByteArray(4)
            assertEquals(4, reader.readAt(50, buffer, 0, 4))
            assertArrayEquals(byteArrayOf(50, 51, 52, 53), buffer)
        }
    }

    @Test
    fun `a read straddling the end is cut short at the end`() {
        RemoteRandomAccess { ByteSourceFile(bytes) }.use { reader ->
            val buffer = ByteArray(10)
            assertEquals(4, reader.readAt(96, buffer, 0, 10))
            assertArrayEquals(byteArrayOf(96, 97, 98, 99), buffer.copyOf(4))
        }
    }

    @Test
    fun `at or past the end is -1, which is what both decoders take for EOF`() {
        RemoteRandomAccess { ByteSourceFile(bytes) }.use { reader ->
            val buffer = ByteArray(10)
            assertEquals(-1, reader.readAt(100, buffer, 0, 10))
            assertEquals(-1, reader.readAt(5_000, buffer, 0, 10))
        }
    }

    @Test
    fun `the first failure is kept and still thrown`() {
        val broken = IOException("share went away")
        val reader = RemoteRandomAccess { throw broken }

        val thrown = runCatching { reader.size() }.exceptionOrNull()
        assertSame(broken, thrown)
        assertSame(broken, reader.failure)
    }

    @Test
    fun `closing a reader that never opened does not open one to close it`() {
        var opened = false
        RemoteRandomAccess {
            opened = true
            ByteSourceFile(bytes)
        }.close()

        assertFalse(opened)
    }

    @Test
    fun `closing a reader closes the file it opened`() {
        val file = ByteSourceFile(bytes)
        RemoteRandomAccess { file }.apply { size() }.close()

        assertTrue(file.closed)
    }
}

/** A [SourceFile] over bytes in memory, answering reads the way the interface promises. */
private class ByteSourceFile(private val bytes: ByteArray) : SourceFile {
    var closed = false
        private set

    override fun length(): Long = bytes.size.toLong()

    override fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun inputStream(): InputStream = ByteArrayInputStream(bytes)

    override fun close() {
        closed = true
    }
}
