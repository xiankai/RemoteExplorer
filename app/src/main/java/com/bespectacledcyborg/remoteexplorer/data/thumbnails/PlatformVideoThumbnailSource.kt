package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.RemoteRandomAccess
import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_SIZE
import com.bespectacledcyborg.remoteexplorer.data.tasks.resizeBitmap
import java.io.File

/**
 * Decodes a frame with the platform's own extractor, reading only the ranges it asks for.
 *
 * [MediaDataSource] hands `MediaMetadataRetriever` a seekable file rather than a buffer, so
 * the extractor locates the metadata itself — including at the *end* of a file that was not
 * written with `+faststart`, which the prefix-copying FFmpeg path cannot reach at all. It
 * also decodes in hardware where the device has a codec for the format.
 *
 * Measured against CX File Explorer, which does exactly this: a median of 786 KB read per
 * video thumbnail, with seeks observed at multi-gigabyte offsets (#41).
 */
class PlatformVideoThumbnailSource(
    private val sambaFileDataSource: SambaFileDataSource,
) : ThumbnailSource {

    override suspend fun render(connectionId: Int, path: String, destination: File): Boolean {
        val reader = RemoteRandomAccess(sambaFileDataSource, connectionId, path)
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(SmbMediaDataSource(reader))
            val frame = retriever.firstUsableFrame()
            if (frame == null) {
                Log.d(TAG, "No decodable frame in $path")
                return false
            }
            resizeBitmap(frame, destination)
            true
        } catch (e: RuntimeException) {
            // The extractor reports everything the same way — IllegalArgumentException for
            // a container it cannot parse, IllegalStateException once it has given up —
            // and it swallows whatever readAt threw on the way. So the reader, not the
            // exception type, decides which of the two failures this was: an unsupported
            // codec is "nothing to show" and answers 404, while a share that went away has
            // to propagate. Catching RuntimeException broadly without this check would
            // silently turn every SMB outage into a missing thumbnail.
            reader.failure?.let { throw it }
            Log.d(TAG, "Platform extractor cannot decode $path", e)
            false
        } finally {
            runCatching { retriever.release() }
            reader.close()
        }
    }

    /**
     * A frame, preferring one a little way in.
     *
     * The very first frame of a video is often black or mid-fade, which makes for a poor
     * grid. Asking for ~10% in costs nothing extra now that seeking is cheap — the extractor
     * jumps there rather than decoding forward to it — and falls back to whatever it can
     * find for a file too short or too odd to seek within.
     */
    private fun MediaMetadataRetriever.firstUsableFrame(): Bitmap? {
        val durationMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        val targetUs = durationMs?.let { it * 1000 / 10 } ?: 0L

        // OPTION_CLOSEST_SYNC takes the nearest keyframe, which is the cheap one to decode.
        return getScaledFrameAtTime(
            targetUs,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            THUMBNAIL_SIZE,
            THUMBNAIL_SIZE,
        ) ?: frameAtTime
    }

    /** Bridges [RemoteRandomAccess] to what the platform extractor expects. */
    private class SmbMediaDataSource(
        private val reader: RemoteRandomAccess,
    ) : MediaDataSource() {

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            reader.readAt(position, buffer, offset, size)

        override fun getSize(): Long = reader.size()

        // The retriever owns neither the handle nor its lifetime; render() closes the
        // reader in its finally block, so closing here as well would double-close.
        override fun close() = Unit
    }

    private companion object {
        const val TAG = "PlatformVideoThumbnailSource"
    }
}
