package com.bespectacledcyborg.remoteexplorer.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnotationParsingTest {

    @Test
    fun `an index record carries the annotation kinds it advertises`() {
        val record = parseTagIndexLine(
            """{"scope":"dir","path":"g/x","tags":["a"],"files":1,"annotations":["narration"]}"""
        )

        assertEquals(listOf(NARRATION), record?.annotations)
    }

    @Test
    fun `an index record without annotations has none`() {
        val record = parseTagIndexLine("""{"scope":"dir","path":"g/x","tags":["a"],"files":1}""")

        assertEquals(emptyList<String>(), record?.annotations)
    }

    @Test
    fun `reads a live narration`() {
        val record = parseAnnotationLine(
            """{"path":"g/x","scope":"dir","id":"gallery-dl:pixiv.caption","motivation":"describing",""" +
                    """"kind":"narration","generator":{"source":"gallery-dl","field":"caption"},""" +
                    """"creator":"author","created":"2026-10-10T17:23:46+0900","source_hash":"abc",""" +
                    """"classifier":{"by":"m","verdict":"narration"},"quality":"narration",""" +
                    """"body":{"value":"placeholder narration","format":"text/plain","language":"ja"}}"""
        )

        assertEquals("g/x", record?.path)
        assertEquals(TagScope.DIRECTORY, record?.scope)
        assertEquals("gallery-dl:pixiv.caption", record?.id)
        assertEquals("placeholder narration", record?.text)
        assertEquals("ja", record?.language)
        assertNull(record?.derivedFrom)
    }

    @Test
    fun `reads a translation with what it was derived from`() {
        val record = parseAnnotationLine(
            """{"path":"g/x","scope":"dir","id":"llm:translate-en:m","kind":"narration",""" +
                    """"quality":"narration","derived_from":{"path":"g/x","id":"gallery-dl:pixiv.caption"},""" +
                    """"body":{"value":"placeholder translation","format":"text/plain","language":"en"}}"""
        )

        assertEquals("gallery-dl:pixiv.caption", record?.derivedFrom)
        assertEquals("en", record?.language)
        assertEquals("placeholder translation", record?.text)
    }

    @Test
    fun `a tombstone has no text`() {
        val record = parseAnnotationLine(
            """{"path":"g/x","scope":"dir","id":"gallery-dl:pixiv.caption","kind":"narration",""" +
                    """"quality":"not","deleted":true}"""
        )

        assertEquals("g/x", record?.path)
        assertNull(record?.text)
    }

    @Test
    fun `a minor verdict is never shown, even with a body`() {
        val record = parseAnnotationLine(
            """{"path":"g/x","scope":"dir","id":"gallery-dl:pixiv.caption","kind":"narration",""" +
                    """"quality":"minor","body":{"value":"placeholder","language":"ja"}}"""
        )

        assertNull(record?.text)
    }

    @Test
    fun `a live record of another kind has no text`() {
        val record = parseAnnotationLine(
            """{"path":"g/x","scope":"dir","id":"whisper:m","kind":"transcript",""" +
                    """"quality":"narration","body":{"value":"placeholder"}}"""
        )

        assertNull(record?.text)
    }

    @Test
    fun `unreadable lines are skipped`() {
        assertNull(parseAnnotationLine(""))
        assertNull(parseAnnotationLine("{\"path\":\"g/x\",\"scope\":\"dir\""))
        assertNull(parseAnnotationLine("""{"path":"g/x","scope":"dir"}"""))
        assertNull(parseAnnotationLine("""{"path":"g/x","scope":"elsewhere","id":"a"}"""))
        assertNull(parseAnnotationLine("""{"path":"","scope":"dir","id":"a"}"""))
    }
}
