package com.bespectacledcyborg.remoteexplorer.utils

import io.ktor.http.decodeURLPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UrlPathTest {

    private fun roundTrip(path: String): String =
        encodeUrlPath(path).split("/").joinToString("/") { it.decodeURLPart() }

    @Test
    fun `a plus in a folder name survives the trip to the server`() {
        val path = "gallery-dl/pixiv/[104293360] 退魔姬  绯红&苍蓝 Chapter1 + IF线 + 人物简介/0.jpg"
        assertEquals(path, roundTrip(path))
    }

    @Test
    fun `spaces, percent signs and reserved characters come back as they went in`() {
        val path = "a b/100% done/c?d#e/f&g=h/ü 日本.png"
        assertEquals(path, roundTrip(path))
    }

    @Test
    fun `a space is never sent as a plus, which a path keeps literally`() {
        assertFalse(encodeUrlPath("two words/x y.jpg").contains('+'))
    }

    @Test
    fun `slashes stay separators`() {
        assertEquals("a/b/c.jpg", encodeUrlPath("a/b/c.jpg"))
    }
}
