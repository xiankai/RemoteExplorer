package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.PathMoveDao

const val PENDING_MOVE_TTL_MILLIS = 14L * 24 * 60 * 60 * 1000

interface PendingMoves {
    suspend fun namesIn(folder: FileIdentity): Set<String>

    suspend fun settle(folder: FileIdentity, listed: Set<String>, now: Long)

    companion object {
        val NONE = object : PendingMoves {
            override suspend fun namesIn(folder: FileIdentity) = emptySet<String>()
            override suspend fun settle(folder: FileIdentity, listed: Set<String>, now: Long) = Unit
        }
    }
}

class StoredPendingMoves(private val dao: PathMoveDao) : PendingMoves {
    override suspend fun namesIn(folder: FileIdentity): Set<String> =
        dao.pendingNames(folder.connectionId, folder.path).toSet()

    override suspend fun settle(folder: FileIdentity, listed: Set<String>, now: Long) {
        if (dao.pendingNames(folder.connectionId, folder.path).isEmpty()) return
        dao.settleListed(folder.connectionId, folder.path, listed, now - PENDING_MOVE_TTL_MILLIS)
    }
}
