package com.bespectacledcyborg.remoteexplorer.data.thumbnails

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
            generateFfmpegVideoThumbnailViaLibraries(destination, handle.inputStream)
        }
        return true
    }
}
