package com.bespectacledcyborg.remoteexplorer.data

import java.io.Closeable
import java.io.InputStream

/**
 * The filesystem operations everything downstream of a connection needs: the listing,
 * the HTTP server, the thumbnail sources and the tag readers.
 *
 * Extracted first so [FileRepository] could be tested without a server, and widened since
 * so that nothing above it names smbj: [openFile] hands back a [SourceFile] rather than an
 * SMBJ handle, which is what lets a second kind of source sit behind the same interface.
 * [SambaFileDataSource] is the only production implementation; tests supply their own.
 */
interface FileDataSource {
    /**
     * Makes [SourceDetails.connectionId] usable. Until it has been called, every other call
     * for that id is refused.
     */
    fun connect(details: SourceDetails)

    fun listFiles(
        connectionId: ConnectionId,
        currentPath: String = "",
        filter: String = "",
    ): List<File>

    /**
     * Opens [path] for reading. The caller owns the handle and closes it: on SMB each one
     * is a server-side resource, and the share runs out of them.
     */
    fun openFile(connectionId: ConnectionId, path: String): SourceFile
}

/**
 * What a source needs to open one connection: [ConnectionDetails] for a share, or
 * [LocalRootDetails] for a folder on the device.
 */
sealed interface SourceDetails {
    val connectionId: ConnectionId
}

/**
 * One file opened for reading, whichever source it came from.
 *
 * Exactly what the consumers do with a handle and no more. The server serves ranges and
 * whole files, the image thumbnailer reads a header and then streams, and both video
 * decoders seek through [RemoteRandomAccess] — so a positional read, a stream from the
 * start, and the length.
 */
interface SourceFile : Closeable {
    /**
     * The file's real length in bytes — end of file, not the space allocated on disk.
     *
     * May go to the source to find out (on SMB it is a query), so a caller that needs it
     * more than once keeps the answer.
     */
    fun length(): Long

    /**
     * Reads up to [length] bytes at file offset [position] into [buffer] at [offset].
     *
     * Returns how many were read, which may be fewer than asked, or -1 at end of file.
     * [RemoteRandomAccess] relies on the -1: it is what `MediaDataSource.readAt` and
     * FFmpeg's read callback both expect to see.
     */
    fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int

    /** The file from its first byte, readable only while this [SourceFile] is open. */
    fun inputStream(): InputStream
}
