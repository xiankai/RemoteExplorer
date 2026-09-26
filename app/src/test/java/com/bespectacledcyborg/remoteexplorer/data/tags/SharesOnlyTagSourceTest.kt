package com.bespectacledcyborg.remoteexplorer.data.tags

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SharesOnlyTagSourceTest {

    private var indexed = mutableListOf<Int>()
    private val delegate = object : TagSource {
        override val capabilities = TagSourceCapabilities(
            indexesUnvisitedDirectories = true,
            supportsWrites = false,
        )

        override suspend fun index(connectionId: Int, reindexAll: Boolean, limit: Int) =
            TagIndexProgress(indexed = 3, mayHaveMore = true).also { indexed += connectionId }
    }
    private val source = SharesOnlyTagSource(delegate, isLocal = { it == 2 })

    @Test
    fun `a share is indexed as before`() = runTest {
        assertEquals(TagIndexProgress(3, mayHaveMore = true), source.index(1, false, 10))
        assertEquals(listOf(1), indexed)
    }

    @Test
    fun `a local root is not indexed at all, and says there is nothing more`() = runTest {
        // mayHaveMore false matters: true would have ScanTagsWorker re-enqueue itself for a
        // connection that will never have anything to do.
        assertEquals(TagIndexProgress.NOTHING, source.index(2, false, 10))
        assertEquals(emptyList<Int>(), indexed)
    }
}
