package com.bespectacledcyborg.remoteexplorer.data.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format, against lines shaped like the generator's real output.
 *
 * Every string below is either copied from the contract or from the fixture the seed script
 * writes, rather than invented here — a parser tested against lines this file made up would
 * agree with itself and nothing else.
 */
class TagIndexParsingTest {

    @Test
    fun `reads a directory record`() {
        val record = parseTagIndexLine(
            """{"scope":"dir","path":"gallery-dl/pixiv/[137128361] 羽衣狐","site":"pixiv",""" +
                    """"site_id":"137128361","title":"羽衣狐","tags":["Original","羽衣狐"],""" +
                    """"files":19,"mtime":1790047563}"""
        )

        assertEquals("gallery-dl/pixiv/[137128361] 羽衣狐", record?.path)
        assertEquals(TagScope.DIRECTORY, record?.scope)
        assertEquals(listOf("Original", "羽衣狐"), record?.tags)
        assertEquals(19, record?.files)
        assertEquals(false, record?.deleted)
    }

    /**
     * Fields this build has no use for must not stop it reading the record. samplebooru already
     * carries `token`, and the generator is free to add more — a parser that refused would
     * turn every future field into a library-wide outage on older installs.
     */
    @Test
    fun `ignores fields it does not know`() {
        val record = parseTagIndexLine(
            """{"scope":"dir","path":"g/x","tags":["a"],"files":1,"token":"deadbeef",""" +
                    """"something_added_later":{"nested":true}}"""
        )

        assertEquals(listOf("a"), record?.tags)
    }

    @Test
    fun `reads a file record`() {
        val record = parseTagIndexLine(
            """{"scope":"file","path":"gallery-dl/patreon/loose files/single.jpg",""" +
                    """"site":"patreon","tags":["creator:somebody"],"mtime":1790047700}"""
        )

        assertEquals(TagScope.FILE, record?.scope)
        // No files count on a file-scoped record, and nothing should invent one — it is
        // what the tag count adds up, so a phantom 1 here would inflate every total.
        assertEquals(0, record?.files)
    }

    @Test
    fun `reads a tombstone`() {
        val record = parseTagIndexLine(
            """{"scope":"dir","path":"g/gone","tags":["x"],"files":0,"deleted":true}"""
        )

        assertTrue(record!!.deleted)
    }

    /**
     * A scope this build does not recognise is skipped rather than guessed at. Guessing FILE
     * for a directory attaches a gallery's tags to nothing; guessing DIRECTORY for a file
     * attaches them to every sibling, which is worse than having no tags at all.
     */
    @Test
    fun `skips a record whose scope it cannot place`() {
        assertNull(parseTagIndexLine("""{"scope":"share","path":"g/x","tags":["a"]}"""))
        assertNull(parseTagIndexLine("""{"path":"g/x","tags":["a"]}"""))
    }

    /**
     * One unreadable line must not abort the pass. The alternative is a single bad record on
     * the share making every tag in the library unreachable.
     */
    @Test
    fun `skips blank and malformed lines`() {
        assertNull(parseTagIndexLine(""))
        assertNull(parseTagIndexLine("   "))
        assertNull(parseTagIndexLine("not json at all"))
        assertNull(parseTagIndexLine("""{"scope":"dir","tags":["a"]}"""))  // no path
        assertNull(parseTagIndexLine("""{"scope":"dir","path":"","tags":["a"]}"""))
    }

    @Test
    fun `splits a namespaced tag on the first colon only`() {
        assertEquals("artist" to "someone", splitNamespacedTag("artist:someone"))
        // Values contain spaces but never colons, so the first colon is the delimiter and
        // everything after it is the value — including any further colons, if one ever
        // appears.
        assertEquals("parody" to "touhou project", splitNamespacedTag("parody:touhou project"))
        assertEquals("a" to "b:c", splitNamespacedTag("a:b:c"))
    }

    /**
     * The meta exactly as the real share had it on 2026-09-26, pretty-printed and all.
     *
     * The fixture used to spell the generation as a string and `built_at` as epoch seconds,
     * and this parser was typed to match — so the real file, with an integer generation and an
     * ISO timestamp, failed to parse on every pass and the index read as absent. Nothing
     * could see it, because an unreadable meta is deliberately treated as no meta.
     */
    @Test
    fun `reads the meta the real generator writes`() {
        val meta = TagIndex.json.decodeFromString<TagIndexMeta>(
            """
            {
              "schema": 1,
              "generation": 2,
              "records": 1375,
              "bytes": 781786,
              "built_at": "2026-09-26T06:00:28+0900"
            }
            """.trimIndent()
        )

        assertEquals(TagIndexMeta(schema = 1, generation = "2", records = 1375, bytes = 781786), meta)
    }

    @Test
    fun `reads a generation spelled as a string too`() {
        val meta = TagIndex.json.decodeFromString<TagIndexMeta>(
            """{"schema":1,"generation":"fixture-1","records":1,"bytes":10}"""
        )
        assertEquals("fixture-1", meta.generation)
    }

    /** Not the string "null" — that would compare equal to itself forever and never re-pull. */
    @Test
    fun `rejects a null generation`() {
        val parsed = runCatching {
            TagIndex.json.decodeFromString<TagIndexMeta>(
                """{"schema":1,"generation":null,"records":1,"bytes":10}"""
            )
        }
        assertTrue(parsed.isFailure)
    }

    @Test
    fun `reads the alias meta the real generator writes`() {
        val meta = TagIndex.json.decodeFromString<TagAliasMeta>(
            """
            {
              "schema": 1,
              "generation": 7,
              "groups": 437,
              "queued": 1654,
              "overrides": 0,
              "built_at": "2026-09-26T12:42:49+0900"
            }
            """.trimIndent()
        )
        assertEquals(TagAliasMeta(schema = 1, generation = "7"), meta)
    }

    /** A line of the real alias file, with the fields this build does not use left in. */
    @Test
    fun `reads an alias group`() {
        val group = parseTagAliasLine(
            """{"canonical": "female/twintails", "confidence": "medium", "members": """ +
                    """["TT", "female:twintails", "twintails", "tt", "ツインテール"], """ +
                    """"needs_root": true, "sources": ["danbooru", "examplebooru-bare"]}"""
        )

        assertEquals("female/twintails", group?.canonical)
        // Case is kept: `TT` and `tt` are both real spellings in the index, and lookups
        // match exactly.
        assertEquals(listOf("TT", "female:twintails", "twintails", "tt", "ツインテール"), group?.members)
    }

    @Test
    fun `skips alias lines that alias nothing`() {
        assertNull(parseTagAliasLine(""))
        assertNull(parseTagAliasLine("{not json"))
        assertNull(parseTagAliasLine("""{"canonical":"","members":["a","b"]}"""))
        assertNull(parseTagAliasLine("""{"canonical":"x/a","members":["a"]}"""))
        assertNull(parseTagAliasLine("""{"canonical":"x/a","members":["a","a",""]}"""))
    }

    @Test
    fun `leaves a bare tag alone`() {
        // pixiv tags are bare and mostly Japanese. Treating one as a namespace would be a
        // silent corruption of a value that cannot be re-derived without the provider.
        assertNull(splitNamespacedTag("羽衣狐"))
        assertNull(splitNamespacedTag(":leading"))
        assertNull(splitNamespacedTag("trailing:"))
    }
}
