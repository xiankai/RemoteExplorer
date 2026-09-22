package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import com.bespectacledcyborg.remoteexplorer.data.tasks.generateFfmpegVideoThumbnailViaLibraries
import java.io.File

/**
 * Decodes the first usable frame with the bundled FFmpeg, on the device.
 *
 * This is the existing behaviour, moved behind [ThumbnailSource] unchanged — including its
 * cost, which is the subject of #28: it reads a growing prefix of the file and re-decodes
 * the whole accumulated buffer on each attempt. Putting it here is what lets that be
 * replaced, or pointed at a server, without touching the caller.
 */
class LocalVideoThumbnailSource(
    private val sambaFileDataSource: SambaFileDataSource,
) : ThumbnailSource {

    override suspend fun render(connectionId: Int, path: String, destination: File): Boolean {
        sambaFileDataSource.openFile(connectionId, path).use { handle ->
            // The decoder throws when it cannot find a frame within its read window — an
            // unsupported codec, a truncated file, or a video whose moov atom sits at the
            // end (see AGENTS.md on +faststart). That is "nothing to show", not a failure of
            // the server, so it becomes false and the caller answers 404. A share that has
            // gone away still throws out of openFile, which is the distinction the interface
            // documents.
            try {
                generateFfmpegVideoThumbnailViaLibraries(destination, handle.inputStream)
            } catch (e: IllegalStateException) {
                Log.d(TAG, "No decodable frame in $path", e)
                return false
            } catch (e: OutOfMemoryError) {
                // A large frame can exhaust the heap between the native buffer and the
                // bitmap. Nothing to show rather than a broken share, and rethrowing would
                // surface it as a 500 on a file that simply cannot be done here.
                Log.d(TAG, "Ran out of memory decoding $path", e)
                return false
            }
        }
        return true
    }

    private companion object {
        const val TAG = "LocalVideoThumbnailSource"
    }
}
