package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.RemoteRandomAccess
import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import com.bespectacledcyborg.remoteexplorer.data.VideoDecoder
import com.bespectacledcyborg.remoteexplorer.data.tasks.resizeBitmap
import java.io.File

/**
 * Decodes the first usable frame with the bundled FFmpeg, on the device.
 *
 * Second in the chain behind [PlatformVideoThumbnailSource], and not redundant: roughly
 * 7.6% of the library is DivX/Xvid `.avi`, which no Android device has a codec for (#41).
 *
 * It now reads through the same [RemoteRandomAccess] the platform extractor uses, so both
 * ends of the chain seek instead of streaming. What this replaced copied a 2 MB prefix into
 * a byte array, told FFmpeg that array was the whole file, and — when the frame was not in
 * it — read another 2 MB and re-probed the whole accumulated buffer, up to nine times. A
 * file whose `moov` atom sits at the end was unreachable however many rounds it ran.
 */
class LocalVideoThumbnailSource(
    private val sambaFileDataSource: SambaFileDataSource,
) : ThumbnailSource {

    override suspend fun render(connectionId: Int, path: String, destination: File): Boolean {
        val reader = RemoteRandomAccess(sambaFileDataSource, connectionId, path)

        return try {
            val frame = VideoDecoder().decodeVideoFrame(reader)
            if (frame == null) {
                // FFmpeg's read callback can only answer with an error code, so a share
                // that went away arrives here looking exactly like an unsupported codec —
                // the same ambiguity the platform extractor has. The reader is what knows
                // the difference, and the interface requires it: false is "nothing to
                // show", a throw is a broken backend.
                reader.failure?.let { throw it }
                Log.d(TAG, "No decodable frame in $path")
                return false
            }
            resizeBitmap(frame, destination)
            true
        } catch (e: OutOfMemoryError) {
            // A large frame can exhaust the heap between the native buffer and the bitmap.
            // Nothing to show rather than a broken share, and rethrowing would surface it
            // as a 500 on a file that simply cannot be done here.
            Log.d(TAG, "Ran out of memory decoding $path", e)
            false
        } finally {
            reader.close()
        }
    }

    private companion object {
        const val TAG = "LocalVideoThumbnailSource"
    }
}
