package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import java.io.File

/**
 * Produces the thumbnail for one kind of media.
 *
 * Implementations render on the device today. Nothing here writes to the share — a source
 * reads the file and writes only into the app's own cache.
 */
interface ThumbnailSource {
    /**
     * Renders a thumbnail for [path] into [destination], returning false when this source
     * cannot produce one for this particular file.
     *
     * False is "nothing to show", not "something went wrong": a failure to reach the share
     * throws, so a caller can tell a file with no usable frame from a share that has gone
     * away.
     */
    suspend fun render(connectionId: Int, path: String, destination: File): Boolean
}

/**
 * Chooses the source for a kind of media.
 *
 * Keyed on the media type rather than on the connection, because images and video do not
 * share economics. An image carries a thumbnail in its own header and is nearly free to do
 * on the device; decoding a video frame is expensive enough that a server already holding
 * decoded frames would be worth configuring. A per-connection switch would have forced both
 * to the same answer, so this is the seam #28 needs to send video elsewhere while images
 * stay local.
 */
fun interface ThumbnailSources {
    /**
     * null when nothing can make a thumbnail for this kind of file.
     *
     * [variant] names a specific implementation instead of the default one for this type.
     * It exists so the two video decoders can be compared on the same build and the same
     * files — see #41 — rather than by rebuilding between measurements, which makes a
     * before/after impossible to trust. An unknown name falls back to the default.
     */
    fun forType(type: IconType, variant: String?): ThumbnailSource?
}
