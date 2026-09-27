package com.bespectacledcyborg.remoteexplorer.data

import LocalDatabaseDataSource
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTagWithMetadata
import com.bespectacledcyborg.remoteexplorer.data.tags.TagIndexProgress
import com.bespectacledcyborg.remoteexplorer.data.tags.TagSourceCapabilities
import com.bespectacledcyborg.remoteexplorer.data.tags.TagSources

const val tagPrefix = "Tag: "

/**
 * Tags, read from Room and kept up to date by whichever [com.bespectacledcyborg.remoteexplorer.data.tags.TagSource]
 * a connection uses.
 *
 * The read path deliberately does not go through the source: browsing by tag stays instant
 * and works offline no matter what filled the index.
 */
class TagRepository(
    private val localDatabaseDataSource: LocalDatabaseDataSource,
    private val tagSources: TagSources,
) {
    suspend fun scanTags(connectionId: Int, forceRemote: Boolean, limit: Int): TagIndexProgress =
        tagSources.forConnection(connectionId).index(connectionId, forceRemote, limit)

    suspend fun getTags(): List<LocalTagWithMetadata> = localDatabaseDataSource.getTags()

    /** Each tag's alias-group siblings, for [TagSearch]. */
    suspend fun getTagSearch(): TagSearch = TagSearch(localDatabaseDataSource.getTagAliasPairs())

    /** What the connection's source can reach, so the UI can be honest about coverage. */
    fun capabilitiesFor(connectionId: Int): TagSourceCapabilities =
        tagSources.forConnection(connectionId).capabilities
}
