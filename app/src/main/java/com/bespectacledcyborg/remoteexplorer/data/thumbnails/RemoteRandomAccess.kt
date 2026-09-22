package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import com.hierynomus.smbj.share.File
import java.io.Closeable

/**
 * Random access to a file on the share, by byte offset.
 *
 * Both video decoders want exactly this and nothing more. Android's
 * [android.media.MediaDataSource] is `readAt(position, buffer, offset, size)` plus a size;
 * FFmpeg's AVIO is a `read_packet` / `seek_packet` pair plus `AVSEEK_SIZE`. They are the
 * same abstraction spelled differently, so it lives here once and each decoder gets a thin
 * adapter — `SmbMediaDataSource` in [PlatformVideoThumbnailSource], and the `ReaderContext`
 * in `ffmpeg_wrapper.c`, which calls [readAt] and [size] straight back across JNI.
 *
 * Reporting the **real** length is the part that matters. The FFmpeg path used to copy a
 * 2 MB prefix into a byte array and answer `AVSEEK_SIZE` with the length of that array, so
 * the demuxer believed every video was 2 MB long. A file with its `moov` atom at the end —
 * anything without `+faststart` — could not be reached at all: the demuxer tried to seek
 * there, correctly, and was refused for being past the end of a file that was not actually
 * that short.
 */
class RemoteRandomAccess(
    private val open: () -> File,
) : Closeable {

    /**
     * The common case: open by connection and path.
     *
     * The lambda form exists for a caller that already has a handle-opening route of its
     * own — [com.bespectacledcyborg.remoteexplorer.data.FileRepository.openFileDirectly] —
     * rather than a [SambaFileDataSource].
     */
    constructor(
        sambaFileDataSource: SambaFileDataSource,
        connectionId: Int,
        path: String,
    ) : this({ sambaFileDataSource.openFile(connectionId, path) })

    private val lazyHandle = lazy { open() }
    private val handle by lazyHandle

    private val length: Long by lazy {
        handle.fileInformation.standardInformation.endOfFile
    }

    /**
     * The file's real length on the share, which is what makes seeking to the tail work.
     *
     * A function rather than a property because `ffmpeg_wrapper.c` looks it up as `size()J`,
     * by name — so renaming it breaks the native decoder at run time rather than at compile
     * time, and `getLength()` would read as an accident on the C side. It is also the first
     * thing either decoder asks for, which is where the handle actually gets opened, so it
     * has to record a failure the same way [readAt] does.
     */
    fun size(): Long = recordingFailure { length }

    /**
     * The first failure this reader hit, if any.
     *
     * A decoder driving this reader cannot distinguish "the share went away" from "this
     * file is not decodable" — both reach it as a failed decode, and the platform extractor
     * in particular swallows whatever `readAt` throws and simply reports that it could not
     * read the media. FFmpeg does the same: its read callback can only answer with an
     * error code. `ThumbnailSource` requires that distinction: false for nothing to show,
     * throw for a broken backend. Recording it here is the only place that knows.
     */
    var failure: Exception? = null
        private set

    /**
     * Reads up to [size] bytes at [position] into [buffer] at [offset]. Returns the number
     * read, or -1 at end of file — the contract `MediaDataSource.readAt` wants, which is
     * also what smbj's offset read already returns.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        recordingFailure {
            if (position >= length) return -1
            // Never ask for more than remains: a read straddling EOF is legal over SMB but
            // the short count that comes back is easy to mistake for an error further up.
            val wanted = minOf(size.toLong(), length - position).toInt()
            if (wanted <= 0) return -1
            handle.read(buffer, position, offset, wanted)
        }

    private inline fun <T> recordingFailure(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (failure == null) failure = e
            throw e
        }

    override fun close() {
        // Only if something actually opened it: touching `handle` here would force the lazy
        // and open a handle purely to close it again.
        if (lazyHandle.isInitialized()) runCatching { handle.close() }
    }
}
