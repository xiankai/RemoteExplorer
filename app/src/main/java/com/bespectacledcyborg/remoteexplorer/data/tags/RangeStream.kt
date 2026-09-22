package com.bespectacledcyborg.remoteexplorer.data.tags

import java.io.InputStream

/** How much to pull per read. Large enough that a full pull is not mostly round trips. */
internal const val CHUNK_BYTES = 256 * 1024

/**
 * `[from, until)` of a randomly-accessible file, as a stream.
 *
 * It exists because everything downstream — the gzip decoder, the line reader — wants a
 * stream, while the thing that makes the index affordable is reading at an offset. A stream
 * that skips to the offset instead would transfer the whole log on every resume in order to
 * throw away all but the tail, which is the cost this design exists to avoid.
 *
 * Takes the read and close as functions rather than the reader itself, so the chunking here
 * can be tested without an SMB server behind it.
 */
internal class RangeStream(
    from: Long,
    private val until: Long,
    private val readAt: (position: Long, buffer: ByteArray, offset: Int, size: Int) -> Int,
    private val closeReader: () -> Unit,
) : InputStream() {

    private var position = from
    private val chunk = ByteArray(CHUNK_BYTES)
    private var chunkOffset = 0
    private var chunkLength = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (chunkOffset >= chunkLength && !fill()) return -1
        // Serves at most what is buffered, which is what InputStream asks for — a caller
        // wanting more calls again. Trying to satisfy len in full here would turn one short
        // read near the end of the range into a loop that cannot terminate.
        val take = minOf(len, chunkLength - chunkOffset)
        chunk.copyInto(b, off, chunkOffset, chunkOffset + take)
        chunkOffset += take
        return take
    }

    override fun available(): Int = (chunkLength - chunkOffset)

    private fun fill(): Boolean {
        if (position >= until) return false
        // Never past `until`: the range ends where the meta file said the log ended, and
        // reading beyond it would pick up records written after the length this pass is
        // resuming against — which would then be missed by the next pass, because its
        // offset starts from that same length.
        val want = minOf(chunk.size.toLong(), until - position).toInt()
        val read = readAt(position, chunk, 0, want)
        if (read <= 0) return false
        position += read
        chunkOffset = 0
        chunkLength = read
        return true
    }

    override fun close() = closeReader()
}
