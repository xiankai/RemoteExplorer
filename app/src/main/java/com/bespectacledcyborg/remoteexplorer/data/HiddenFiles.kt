package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.tags.TagIndex

object HiddenFiles {
    private val windowsLitter = setOf("thumbs.db", "desktop.ini")
    private val databaseSidecarSuffixes = listOf("-journal", "-wal", "-shm")
    private val databaseExtensions = setOf("sqlite", "sqlite3", "db", "db3")

    fun isHidden(name: String, canonicalPath: String): Boolean =
        name.startsWith(".") ||
            name.lowercase() in windowsLitter ||
            isDatabase(name) ||
            Pairings.normalize(canonicalPath) == TagIndex.DIRECTORY

    private fun isDatabase(name: String): Boolean {
        val lower = name.lowercase()
        val base = databaseSidecarSuffixes.firstOrNull { lower.endsWith(it) }
            ?.let { lower.removeSuffix(it) }
            ?: lower
        val extension = base.substringAfterLast('.', "")
        return extension in databaseExtensions
    }

    fun visible(
        files: List<LocalFile>,
        canonicalize: (ConnectionId, String) -> FileIdentity,
    ): List<LocalFile> = files.filterNot { file ->
        isHidden(file.filename, canonicalize(file.connectionId, file.fullPath()).path)
    }

    fun visiblePaths(
        connectionId: ConnectionId,
        paths: List<String>,
        canonicalize: (ConnectionId, String) -> FileIdentity,
    ): List<String> = paths.filterNot { path ->
        isHidden(path.substringAfterLast('/'), canonicalize(connectionId, path).path)
    }
}
