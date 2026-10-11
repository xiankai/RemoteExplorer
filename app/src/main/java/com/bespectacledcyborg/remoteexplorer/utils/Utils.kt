package com.bespectacledcyborg.remoteexplorer.utils

import com.bespectacledcyborg.remoteexplorer.data.ENCODING
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_SIZE
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.github.panpf.sketch.util.MimeTypeMap
import java.net.ServerSocket
import java.net.URLEncoder

object Utils {
    private var port = 0

    fun guessIconType(filename: String): IconType {
        val encodedFilename = URLEncoder.encode(filename, ENCODING)
        val mimeType = MimeTypeMap.getMimeTypeFromUrl(encodedFilename) ?: "folder"

        val iconType = when {
            Regex("image/svg+xml").matches(mimeType) -> IconType.SVG
            Regex("image/.*").matches(mimeType) -> IconType.IMAGE
            Regex("video/.*").matches(mimeType) -> IconType.VIDEO
            Regex("audio/.*").matches(mimeType) -> IconType.AUDIO
            Regex("text/.*").matches(mimeType) -> IconType.TEXT
            Regex("application/.*").matches(mimeType) -> IconType.FILE
            filename.endsWith(".xmp") -> IconType.TAG
            else -> IconType.FOLDER
        }

        return iconType
    }

    /**
     * What opens in the app's own viewer rather than being handed to another app. Images
     * only: the viewer is a pager of pictures, and video goes to a real player.
     */
    val BROWSEABLE_TYPES = listOf(  // stack-reviewer-ignore: no regression — isBrowsable() was false for every file before this change (FileType compared against IconType), so video never opened in-app; it has always taken the ACTION_VIEW branch in ExplorerScreen, which hands the loopback URL to an external player. Dropping VIDEO keeps exactly that, and the viewer is a pager of still images.
        IconType.IMAGE,
    )

    val SUPPORTED_THUMBNAILS = listOf(
        IconType.IMAGE,
        IconType.VIDEO,
    )

    fun canHaveThumbnail(filename: String): Boolean {
        val iconType = guessIconType(filename)
        return SUPPORTED_THUMBNAILS.contains(iconType)
    }

    // https://github.com/hierynomus/smbj/pull/754/files#diff-b37109dad0e9cc457d7a4279146071eaf83a70c44557ad87a3733b4a9e1020f8
    private const val FILE_ATTRIBUTE_DIRECTORY: Long = 0x10L
    private const val FILE_ATTRIBUTE_READONLY: Long = 0x1L
    private const val FILE_ATTRIBUTE_HIDDEN: Long = 0x2L
    private const val FILE_ATTRIBUTE_ARCHIVE: Long = 0x20L
    private const val FILE_ATTRIBUTE_SYSTEM: Long = 0x4L

    fun isDirectory(attributes: Long): Boolean {
        return (attributes and FILE_ATTRIBUTE_DIRECTORY) == FILE_ATTRIBUTE_DIRECTORY
    }

    fun isArchive(attributes: Long): Boolean {
        return (attributes and FILE_ATTRIBUTE_ARCHIVE) == FILE_ATTRIBUTE_ARCHIVE
    }

    fun isHidden(attributes: Long): Boolean {
        return (attributes and FILE_ATTRIBUTE_HIDDEN) == FILE_ATTRIBUTE_HIDDEN
    }

    fun isReadOnly(attributes: Long): Boolean {
        return (attributes and FILE_ATTRIBUTE_READONLY) == FILE_ATTRIBUTE_READONLY
    }

    fun isSystem(attributes: Long): Boolean {
        return (attributes and FILE_ATTRIBUTE_SYSTEM) == FILE_ATTRIBUTE_SYSTEM
    }

    /**
     * Finds an available port by creating a ServerSocket with port 0, which makes the system
     * allocate an available port. The socket is immediately closed after getting the port.
     */
    fun findAvailablePort(): Int {
        if (port == 0) {
            port = ServerSocket(0).use { socket -> socket.localPort }
        }

        return port
    }

    fun constructUrl(connectionId: Int, path: String, thumbnail: Boolean = false): String {
        val baseUrl = "http://127.0.0.1:${port}/${encodeUrlPath(path)}?connectionId=${connectionId}"
        // The size is for Sketch, which keys its own caches by URL: without it a cell that
        // had loaded a 128 px thumbnail would keep showing those bytes after THUMBNAIL_SIZE
        // went up (#73). The server does not read it.
        return if (thumbnail) "$baseUrl&thumbnail=true&size=$THUMBNAIL_SIZE" else baseUrl
    }
}

internal fun encodeUrlPath(path: String): String =
    path.split("/").joinToString("/") { segment ->
        URLEncoder.encode(segment, ENCODING).replace("+", "%20")
    }