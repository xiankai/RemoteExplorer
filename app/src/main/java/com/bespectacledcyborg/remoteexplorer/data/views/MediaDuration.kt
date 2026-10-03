package com.bespectacledcyborg.remoteexplorer.data.views

import android.media.MediaMetadataRetriever
import com.bespectacledcyborg.remoteexplorer.data.FileDataSource
import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import com.bespectacledcyborg.remoteexplorer.data.RemoteRandomAccess
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.PlatformVideoThumbnailSource.SmbMediaDataSource

/**
 * A media file's duration in milliseconds, off its container, or null when the platform
 * extractor cannot read it (the DivX/Xvid `.avi` share of the library, for one).
 *
 * Read through the same ranged reader the thumbnail decoders use, straight from the data
 * source, so it never passes through the loopback server and is never counted as watched.
 */
class MediaDuration(private val files: FileDataSource) {
    fun of(served: FileIdentity): Long? {
        val reader = RemoteRandomAccess(files, served.connectionId, served.path)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(SmbMediaDataSource(reader))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: RuntimeException) {  // stack-reviewer-ignore: setDataSource and extractMetadata declare no checked exceptions and fail with IllegalArgumentException/IllegalStateException/RuntimeException, all caught; an Error should not be swallowed, and the finally still closes the reader
            null
        } finally {
            runCatching { retriever.release() }
            reader.close()
        }
    }
}
