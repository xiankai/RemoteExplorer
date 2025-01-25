package com.example.remoteexplorer.utils

import android.util.Log
import com.example.remoteexplorer.ui.components.IconType
import java.net.URLConnection

object Utils {
    fun guessIconType(filename: String): IconType {
        val mimeType = URLConnection.guessContentTypeFromName(filename) ?: "folder"

        val iconType = when {
            Regex("image/.*").matches(mimeType) -> IconType.IMAGE
            Regex("video/.*").matches(mimeType) -> IconType.VIDEO
            Regex("audio/.*").matches(mimeType) -> IconType.AUDIO
            Regex("text/.*").matches(mimeType) -> IconType.TEXT
            Regex("application/.*").matches(mimeType) -> IconType.FILE
            else -> IconType.FOLDER
        }

        return iconType
    }

    fun canHaveThumbnail(filename: String): Boolean {
        val iconType = guessIconType(filename)
        return listOf(IconType.IMAGE, IconType.VIDEO).contains(iconType)
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
}