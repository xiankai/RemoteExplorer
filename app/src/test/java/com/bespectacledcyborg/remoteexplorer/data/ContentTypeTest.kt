package com.bespectacledcyborg.remoteexplorer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentTypeTest {

    @Test
    fun `a known extension keeps its type`() {
        assertEquals("image/jpeg", contentTypeOf("gallery/0.jpg"))
    }

    @Test
    fun `a name with no known type is served as bytes`() {
        assertEquals("application/octet-stream", contentTypeOf("gallery-dl/.DS_Store"))
        assertEquals("application/octet-stream", contentTypeOf("immich/thumbs/.immich"))
    }
}
