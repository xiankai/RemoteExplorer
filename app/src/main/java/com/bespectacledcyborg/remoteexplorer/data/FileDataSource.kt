package com.bespectacledcyborg.remoteexplorer.data

/**
 * The remote-filesystem operations [FileRepository] needs.
 *
 * Extracted so the repository can be tested without a server. [SambaFileDataSource] is the
 * only production implementation; tests supply their own.
 *
 * [openFile] is here because the repository calls it, not because it is fakeable: it hands
 * back an SMBJ handle that only a real server can produce. A fake is expected to throw for
 * it. The behaviour worth asserting — which listings come from the cache and which go to
 * the server, and how the filter is applied — lives entirely in [connect] and [listFiles].
 */
interface FileDataSource {
    fun connect(details: ConnectionDetails)

    fun listFiles(
        connectionId: ConnectionId,
        currentPath: String = "",
        filter: String = "",
    ): List<File>

    fun openFile(connectionId: ConnectionId, path: String): com.hierynomus.smbj.share.File
}
