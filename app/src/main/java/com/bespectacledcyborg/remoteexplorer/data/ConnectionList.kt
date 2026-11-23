package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import java.io.File

class ConnectionList(
    private val connections: List<Connection>,
    private val pairings: List<Pairing>,
    isPresent: (Connection) -> Boolean,
) {
    private val presentLocalRootIds: Set<Int> = connections
        .filter { it.type == ConnectionType.LOCAL && isPresent(it) }
        .mapTo(HashSet()) { it.id }

    private val shareIds: Set<Int> = connections
        .filter { it.type == ConnectionType.SMB }
        .mapTo(HashSet()) { it.id }

    private val sharesWithAPresentCopy: Set<Int> = pairings
        .filter { it.localConnectionId in presentLocalRootIds && it.remoteConnectionId in shareIds }
        .mapTo(HashSet()) { it.remoteConnectionId }

    fun shown(): List<Connection> = connections.filter { it.id !in sharesWithAPresentCopy }

    fun entryFor(location: Location): Location {
        if (location.connection.id !in sharesWithAPresentCopy) return location
        val copy = Pairings(pairings)
            .localCopiesOf(FileIdentity(location.connection.id, Pairings.normalize(location.path)))
            .firstOrNull { it.connectionId in presentLocalRootIds }
            ?: return location
        val localRoot = connections.first { it.id == copy.connectionId }
        return Location(localRoot, copy.path)
    }

    companion object {
        fun isReadableLocalRoot(connection: Connection): Boolean {
            val root = connection.localRoot ?: return false
            val folder = File(root)
            return folder.isDirectory && folder.canRead()
        }
    }
}
