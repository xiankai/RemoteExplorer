package com.bespectacledcyborg.remoteexplorer.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class EntrySide { NAS_ONLY, TABLET_ONLY }

class EntrySides(
    private val local: LocalFileDataSource,
    private val isLocalRoot: suspend (ConnectionId) -> Boolean,
    private val pairings: () -> Pairings,
    private val shareNamesLastListed: (ConnectionId, String) -> Set<String>?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scopes = SyncScopes(local, isLocalRoot, pairings)

    suspend fun of(connectionId: ConnectionId, parentPath: String, names: Collection<String>): Map<String, EntrySide> =
        withContext(dispatcher) {
            val folder = FileIdentity(connectionId, Pairings.normalize(parentPath))
            if (!isLocalRoot(connectionId) || pairings().pairingOf(connectionId, folder.path) == null) {
                return@withContext emptyMap()
            }
            if (!local.isFolder(connectionId, "")) return@withContext emptyMap()
            val onTablet = if (local.isFolder(connectionId, folder.path)) {
                local.namesIn(connectionId, folder.path).toSet()
            } else {
                emptySet()
            }
            val tabletOnly = tabletOnlyTest(folder)
            names.mapNotNull { name ->
                when {
                    name !in onTablet -> name to EntrySide.NAS_ONLY
                    tabletOnly(name) -> name to EntrySide.TABLET_ONLY
                    else -> null
                }
            }.toMap()
        }

    private suspend fun tabletOnlyTest(folder: FileIdentity): (String) -> Boolean {
        if (scopes.inside(folder) != SyncScope.Unsynced) return { false }
        val onShare = shareNamesLastListed(folder.connectionId, folder.path) ?: return { false }
        return { name ->
            name.lowercase() !in onShare &&
                !local.isSyncthingFolder(folder.connectionId, Pairings.join(folder.path, name))
        }
    }
}
