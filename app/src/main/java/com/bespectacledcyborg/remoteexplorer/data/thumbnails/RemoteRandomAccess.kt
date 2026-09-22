package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import java.io.Closeable

/**
 * Random access to a file on the share, by byte offset.
 *
 * Both video decoders want exactly this and nothing more. Android's
 * [android.media.MediaDataSource] is `readAt(position, buffer, offset, size)` plus a size;
 * FFmpeg's AVIO is a `read_packet` / `seek_packet` pair plus `AVSEEK_SIZE`. They are the
 * same abstraction spelled differently, so it lives here once and each decoder gets a thin
 * adapter.
 *
 * Reporting the **real** length is the part that matters. The previous FFmpeg path copied a
 * 2 MB prefix into a byte array and answered `AVSEEK_SIZE` with the length of that array, so
 * the demuxer believed every video was 2 MB long. A file with its `moov` atom at the end —
 * anything without `+faststart` — could not be reached at all: the demuxer tried to seek
 * there, correctly, and was refused for being past the end of a file that was not actually
 * that short.
 */
class RemoteRandomAccess(
    private val sambaFileDataSource: SambaFileDataSource,
    private val connectionId: Int,
    private val path: String,
) : Closeable {

    private val lazyHandle = lazy { sambaFileDataSource.openFile(connectionId, path) }
    private val handle by lazyHandle

    /** The file's real length on the share, which is what makes seeking to the tail work. */
    val length: Long by lazy {
        handle.fileInformation.standardInformation.endOfFile
    }

    /**
     * The first failure this reader hit, if any.
     *
     * A decoder driving this reader cannot distinguish "the share went away" from "this
     * file is not decodable" — both reach it as a failed decode, and the platform extractor
     * in particular swallows whatever `readAt` throws and simply reports that it could not
     * read the media. `ThumbnailSource` requires that distinction: false for nothing to
     * show, throw for a broken backend. Recording it here is the only place that knows.
     */
    var failure: Exception? = null
        private set

    /**
     * Reads up to [size] bytes at [position] into [buffer] at [offset]. Returns the number
     * read, or -1 at end of file — the contract `MediaDataSource.readAt` wants, which is
     * also what smbj's offset read already returns.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        try {
            if (position >= length) return -1
            // Never ask for more than remains: a read straddling EOF is legal over SMB but
            // the short count that comes back is easy to mistake for an error further up.
            val wanted = minOf(size.toLong(), length - position).toInt()
            if (wanted <= 0) return -1
            return handle.read(buffer, position, offset, wanted)
        } catch (e: Exception) {
            if (failure == null) failure = e
            throw e
        }
    }

    override fun close() {
        // Only if something actually opened it: touching `handle` here would force the lazy
        // and open a handle purely to close it again.
        if (lazyHandle.isInitialized()) runCatching { handle.close() }
    }
}
