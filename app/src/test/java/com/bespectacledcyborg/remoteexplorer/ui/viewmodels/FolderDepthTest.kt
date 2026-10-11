package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderDepthTest {
    @Test
    fun `folder depth is bucketed so the attribute stays low-cardinality`() {
        assertEquals(
            listOf("0", "1", "3", "4+"),
            listOf("", "gallery-dl", "gallery-dl/pixiv/a", "a/b/c/d/e").map(::depthOf),
        )
    }
}
