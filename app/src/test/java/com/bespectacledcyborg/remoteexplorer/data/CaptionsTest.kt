package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.DirAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionsTest {

    private fun row(
        id: String,
        text: String,
        language: String?,
        derivedFrom: String? = null,
        supersedes: String? = null,
    ) = DirAnnotation(1, "g/x", id, text, language, derivedFrom, supersedes)

    @Test
    fun `an English source is shown as it is`() {
        val caption = pickCaption(listOf(row("gallery-dl:pixiv.caption", "source", "en")))

        assertEquals(Caption("source", original = null, originalLanguage = null), caption)
    }

    @Test
    fun `a translated source shows English first with the original behind it`() {
        val caption = pickCaption(
            listOf(
                row("gallery-dl:pixiv.caption", "source", "ja"),
                row("llm:translate-en:m", "translation", "en", derivedFrom = "gallery-dl:pixiv.caption"),
            )
        )

        assertEquals(Caption("translation", original = "source", originalLanguage = "ja"), caption)
    }

    @Test
    fun `an untranslated source is shown in its own language`() {
        val caption = pickCaption(listOf(row("gallery-dl:pixiv.caption", "source", "zh")))

        assertEquals(Caption("source", original = null, originalLanguage = "zh"), caption)
    }

    @Test
    fun `a translation whose source is gone is not shown`() {
        val caption = pickCaption(
            listOf(row("llm:translate-en:m", "translation", "en", derivedFrom = "gallery-dl:pixiv.caption"))
        )

        assertNull(caption)
    }

    @Test
    fun `a manual caption replaces the one it supersedes`() {
        val caption = pickCaption(
            listOf(
                row("gallery-dl:pixiv.caption", "source", "en"),
                row("manual:kj", "edited", "en", supersedes = "gallery-dl:pixiv.caption"),
            )
        )

        assertEquals("edited", caption?.text)
    }

    @Test
    fun `a manual caption replaces a translation it supersedes`() {
        val caption = pickCaption(
            listOf(
                row("gallery-dl:pixiv.caption", "source", "ja"),
                row("llm:translate-en:m", "translation", "en", derivedFrom = "gallery-dl:pixiv.caption"),
                row("manual:kj", "edited", "en", supersedes = "llm:translate-en:m"),
            )
        )

        assertEquals("edited", caption?.text)
    }

    @Test
    fun `nothing to show is no caption`() {
        assertNull(pickCaption(emptyList()))
    }
}
