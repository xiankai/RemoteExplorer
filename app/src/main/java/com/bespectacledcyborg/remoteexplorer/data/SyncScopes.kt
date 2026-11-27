package com.bespectacledcyborg.remoteexplorer.data

const val SYNC_ROOT_SEARCH_FOLDERS = 2_000

sealed interface SyncScope {
    data object Unpaired : SyncScope
    data object Unsynced : SyncScope
    data class Synced(val folder: FileIdentity) : SyncScope
}

class SyncScopes(
    private val local: LocalFileDataSource,
    private val isLocalRoot: suspend (ConnectionId) -> Boolean,
    private val pairings: () -> Pairings,
) {
    suspend fun ofEntry(entry: FileIdentity): SyncScope {
        val frame = tabletFrameOf(entry) ?: return SyncScope.Unpaired
        return insideFolder(frame.connectionId, Pairings.normalize(frame.path).substringBeforeLast('/', ""))
    }

    suspend fun inside(folder: FileIdentity): SyncScope {
        val frame = tabletFrameOf(folder) ?: return SyncScope.Unpaired
        return insideFolder(frame.connectionId, frame.path)
    }

    suspend fun isSyncRoot(entry: FileIdentity): Boolean {
        val frame = tabletFrameOf(entry) ?: return false
        return frame.path.isNotEmpty() && local.isSyncthingFolder(frame.connectionId, frame.path)
    }

    suspend fun holdsSyncRoot(entry: FileIdentity): Boolean {
        val frame = tabletFrameOf(entry) ?: return false
        return local.holdsSyncthingFolder(frame.connectionId, frame.path, SYNC_ROOT_SEARCH_FOLDERS)
    }

    private fun insideFolder(connectionId: ConnectionId, folder: String): SyncScope {
        val segments = folder.split('/').filter { it.isNotEmpty() }
        for (depth in segments.size downTo 0) {
            val candidate = segments.take(depth).joinToString("/")
            if (local.isSyncthingFolder(connectionId, candidate)) {  // stack-reviewer-ignore: Syncthing never deletes .stfolder; a missing marker is the "folder marker missing" error that stops the folder, so nothing syncs there and reading it as unsynced is the safe side (owner's ruling A on #225)
                return SyncScope.Synced(FileIdentity(connectionId, candidate))
            }
        }
        return SyncScope.Unsynced
    }

    private suspend fun tabletFrameOf(identity: FileIdentity): FileIdentity? {
        val path = Pairings.normalize(identity.path)
        val current = pairings()
        if (isLocalRoot(identity.connectionId)) {
            return FileIdentity(identity.connectionId, path)
                .takeIf { current.pairingOf(identity.connectionId, path) != null }
        }
        return current.localCopiesOf(current.canonicalize(identity.connectionId, path)).firstOrNull()
    }
}
