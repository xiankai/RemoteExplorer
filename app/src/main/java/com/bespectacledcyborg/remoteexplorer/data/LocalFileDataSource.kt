package com.bespectacledcyborg.remoteexplorer.data

import android.os.Environment
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** SMB's directory bit, which is what [FileRepository] reads a folder off. See [Utils]. */
private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L

/**
 * A folder on the device's own storage, read with plain `java.io.File` under all-files
 * access.
 *
 * Each root is confined: every path is resolved and canonicalised, and anything that lands
 * outside the root is refused. That matters more here than it looks. The HTTP server
 * re-serves whatever this returns to any app on the device that knows a connection id, and
 * all-files access means this process can read the whole of shared storage — so the root
 * is the only thing standing between "the synced folder" and "everything on the phone".
 *
 * Syncthing's own files are invisible, in listings and to [openFile]: see
 * [isSyncthingInternal].
 */
class LocalFileDataSource(
    // Injected so a JVM test can run against a temp directory: the real check is an
    // Android stub that throws off-device.
    private val hasAllFilesAccess: () -> Boolean = Environment::isExternalStorageManager,
) : FileDataSource, EntryTree {
    private val roots = ConcurrentHashMap<ConnectionId, java.io.File>()

    override fun connect(details: SourceDetails) {
        require(details is LocalRootDetails) { "Not a local root: $details" }
        if (!hasAllFilesAccess()) throw AllFilesAccessMissing()
        val root = java.io.File(details.root).canonicalFile
        if (!root.isDirectory) throw FileNotFoundException("${details.root} is not a folder")
        roots[details.connectionId] = root
    }

    override fun listFiles(
        connectionId: ConnectionId,
        currentPath: String,
        filter: String,
    ): List<File> {
        val directory = resolve(connectionId, currentPath)
        val children = directory.listFiles()
            ?: throw FileNotFoundException("Cannot list $currentPath")
        val pattern = filter.takeIf { it.isNotBlank() }?.let(::wildcardRegex)

        return children.mapNotNull { child ->
            if (isSyncthingInternal(child.name)) return@mapNotNull null
            if (pattern != null && !pattern.matches(child.name)) return@mapNotNull null
            File(
                filename = child.name,
                parentPath = currentPath,
                lastModified = Date(child.lastModified()),
                allocationSize = if (child.isFile) child.length() else 0L,
                fileAttributes = if (child.isDirectory) FILE_ATTRIBUTE_DIRECTORY else 0L,
            )
        }
    }

    override fun openFile(connectionId: ConnectionId, path: String): SourceFile {
        val file = resolve(connectionId, path)
        if (!file.isFile) throw FileNotFoundException(path)
        return LocalSourceFile(RandomAccessFile(file, "r"))
    }

    override fun exists(connectionId: ConnectionId, path: String): Boolean =
        runCatching { resolve(connectionId, path).exists() }.getOrDefault(false)

    override fun isFolder(connectionId: ConnectionId, path: String): Boolean =
        runCatching { resolve(connectionId, path).isDirectory }.getOrDefault(false)

    override fun namesIn(connectionId: ConnectionId, folder: String): List<String> =
        resolve(connectionId, folder).list()?.toList().orEmpty()

    override fun createFolder(connectionId: ConnectionId, path: String) {
        Files.createDirectory(leaf(connectionId, path).toPath())
    }

    override fun move(connectionId: ConnectionId, from: String, to: String) {
        val source = resolve(connectionId, from)
        val target = leaf(connectionId, to)
        if (!source.exists()) throw FileNotFoundException(from)
        val caseOnly = target.exists() && Files.isSameFile(source.toPath(), target.toPath())
        if (target.exists() && !caseOnly) throw FileAlreadyExistsException(target.toPath().toString())
        if (caseOnly) {
            val step = java.io.File(source.parentFile, ".${source.name}.rename-${System.nanoTime()}")
            Files.move(source.toPath(), step.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(step.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(step.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw e
            }
        } else {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override fun filesUnder(connectionId: ConnectionId, path: String, cap: Int): List<String> {
        val start = resolve(connectionId, path)
        if (start.isFile) return listOf(Pairings.normalize(path))
        return start.walkTopDown()
            .onEnter { !isSyncthingInternal(it.name) || it == start }
            .filter { it.isFile && !isSyncthingInternal(it.name) }
            .map { Pairings.join(path, it.relativeTo(start).invariantSeparatorsPath) }
            .take(cap)
            .toList()
    }

    fun measure(connectionId: ConnectionId, path: String, fileCap: Int): EntrySize {
        val start = resolve(connectionId, path)
        var files = 0
        var bytes = 0L
        for (file in start.walkTopDown().onEnter { !isSyncthingInternal(it.name) || it == start }) {
            if (!file.isFile) continue
            files++
            bytes += file.length()
            if (files >= fileCap) return EntrySize(files, bytes, complete = false)
        }
        return EntrySize(files, bytes, complete = true)
    }

    private fun leaf(connectionId: ConnectionId, path: String): java.io.File {
        val name = path.substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == ".." || isSyncthingInternal(name)) {
            throw FileNotFoundException(path)
        }
        return java.io.File(resolve(connectionId, path.substringBeforeLast('/', "")), name)
    }

    /**
     * [path] under the connection's root, or a [FileNotFoundException].
     *
     * Missing rather than forbidden for everything refused, including a path that escapes
     * the root: the caller's question is "is there a file here", and an escape attempt
     * deserves the same answer as a typo.
     */
    private fun resolve(connectionId: ConnectionId, path: String): java.io.File {
        val root = roots[connectionId]
            ?: throw UnknownConnectionException(connectionId, "not initialized")
        // Every segment, not just the leaf: `.stversions/photo.jpg` is a real file, and
        // it is exactly the one that must not be served under a shared thumbnail key.
        if (path.split('/').any { isSyncthingInternal(it) }) throw FileNotFoundException(path)

        val resolved = java.io.File(root, path).canonicalFile
        if (resolved != root && !resolved.path.startsWith(root.path + java.io.File.separator)) {
            throw FileNotFoundException(path)
        }
        return resolved
    }
}

data class EntrySize(val files: Int, val bytes: Long, val complete: Boolean)

/** What [LocalFileDataSource] needs to open a root. */
data class LocalRootDetails(
    override val connectionId: ConnectionId,
    /** An absolute path on the device. */
    val root: String,
) : SourceDetails

/** The user has not granted, or has since revoked, all-files access. */
class AllFilesAccessMissing : IllegalStateException("All files access is not granted")

/**
 * Whether [name] is one of Syncthing's own files rather than something it synced.
 *
 * `.stfolder` marks the root, `.stignore` holds the exclusions, `.stversions` keeps old
 * copies, and a file in flight is written under `.syncthing.<name>.tmp` or `~syncthing~<name>`
 * before being renamed into place. None of them is the user's, and a temp file is worse
 * than clutter: once thumbnails are shared with the NAS copy, a half-written frame would be
 * cached under the real file's key.
 */
internal fun isSyncthingInternal(name: String): Boolean =
    name == ".stfolder" ||
            name == ".stignore" ||
            name == ".stversions" ||
            name.startsWith("~syncthing~") ||
            (name.startsWith(".syncthing.") && name.endsWith(".tmp"))

/** SMB's search pattern, `*` and `?`, case-insensitive the way the share treats it. */
private fun wildcardRegex(pattern: String): Regex =
    pattern.split('*').joinToString(".*") { part ->
        part.split('?').joinToString(".") { Regex.escape(it) }
    }.toRegex(RegexOption.IGNORE_CASE)

/** A [SourceFile] over a local file. */
private class LocalSourceFile(
    private val file: RandomAccessFile,
) : SourceFile {
    override fun length(): Long = file.length()

    // A positional read on the channel, so a stream opened alongside it is not moved.
    override fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int =
        file.channel.read(ByteBuffer.wrap(buffer, offset, length), position)

    override fun inputStream(): InputStream = RandomAccessInputStream(file)

    override fun close() = file.close()
}

/** Streams [file] from the start by positional reads, leaving its own pointer alone. */
private class RandomAccessInputStream(private val file: RandomAccessFile) : InputStream() {
    private var position = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val read = file.channel.read(ByteBuffer.wrap(b, off, len), position)
        if (read > 0) position += read
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = n.coerceIn(0L, maxOf(0L, file.length() - position))
        position += skipped
        return skipped
    }
}
