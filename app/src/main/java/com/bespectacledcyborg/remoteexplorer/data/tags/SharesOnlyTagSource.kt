package com.bespectacledcyborg.remoteexplorer.data.tags

/**
 * Indexes shares and skips local roots.
 *
 * Every source behind this reaches a share: the index and aliases read through SMB, and a
 * local root is not a connection SMB knows, so indexing one failed the scan on every
 * listing. Nor is a local copy meant to be indexed in its own right: its tags are the NAS's,
 * reached through the pairing (#51), and indexing the copy as well would count every tag
 * twice in a list that spans connections.
 *
 * [isLocal] asks the database rather than anything learned at connect time, because a scan
 * can run from WorkManager in a process that has not connected anything yet.
 */
class SharesOnlyTagSource(
    private val delegate: TagSource,
    private val isLocal: suspend (connectionId: Int) -> Boolean,
) : TagSource {
    override val capabilities: TagSourceCapabilities get() = delegate.capabilities

    override suspend fun index(
        connectionId: Int,
        reindexAll: Boolean,
        limit: Int,
    ): TagIndexProgress =
        if (isLocal(connectionId)) {
            TagIndexProgress.NOTHING
        } else {
            delegate.index(connectionId, reindexAll, limit)
        }
}
