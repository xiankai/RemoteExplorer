package com.bespectacledcyborg.remoteexplorer.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Sends each connection to the source that serves it: SMB for a share, the filesystem for
 * a local root.
 *
 * Everything downstream — the listing, the HTTP server, the thumbnail sources — holds this
 * one instance and addresses files by connection id alone, so a new kind of source is a
 * line here rather than a branch at every call site. Which source a connection belongs to
 * is learned when it connects, the same moment SMB learns its share: a connection that has
 * not connected is refused by both, so there is nothing to remember across it.
 */
class RoutingFileDataSource(
    private val smb: FileDataSource,
    private val local: FileDataSource,
) : FileDataSource {
    private val routes = ConcurrentHashMap<ConnectionId, FileDataSource>()

    override fun connect(details: SourceDetails) {
        val source = when (details) {
            is ConnectionDetails -> smb
            is LocalRootDetails -> local
        }
        source.connect(details)
        // Only once the connect succeeded, and replacing any earlier route: a connection
        // edited from one kind to the other keeps its id.
        routes[details.connectionId] = source
    }

    override fun listFiles(connectionId: ConnectionId, currentPath: String, filter: String) =
        route(connectionId).listFiles(connectionId, currentPath, filter)

    override fun openFile(connectionId: ConnectionId, path: String) =
        route(connectionId).openFile(connectionId, path)

    /** Whether [connect] has succeeded for [connectionId] in this process. */
    fun isConnected(connectionId: ConnectionId): Boolean = routes.containsKey(connectionId)

    private fun route(connectionId: ConnectionId): FileDataSource =
        routes[connectionId]
            ?: throw IllegalStateException("Connection ID: $connectionId not initialized")
}
