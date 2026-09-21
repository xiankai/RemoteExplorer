package com.bespectacledcyborg.remoteexplorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers what byte range a `Range:` header asks for.
 *
 * The range server had no tests at all, and two of the cases below were wrong in ways that
 * silently served the wrong bytes rather than failing: a suffix range handed back the front
 * of the file instead of the end, and a start past EOF was clamped down to the last byte and
 * reported as a success. Both are the kind of thing a video player hits and a person reads
 * as "seeking is broken" without any error to go on.
 */
class RangeHeaderTest {

    private val total = 1_000L

    @Test
    fun `no range header means the whole file`() {
        assertEquals(LongRange(0, 999), parseRangeHeaders(null, total))
    }

    @Test
    fun `an explicit range is taken as given`() {
        assertEquals(LongRange(100, 199), parseRangeHeaders("bytes=100-199", total))
    }

    @Test
    fun `an open-ended range runs to the last byte`() {
        assertEquals(LongRange(500, 999), parseRangeHeaders("bytes=500-", total))
    }

    @Test
    fun `an end past the last byte is clamped rather than rejected`() {
        // Asking for more than exists is legal and means "to the end" — unlike a START past
        // the end, which is not.
        assertEquals(LongRange(900, 999), parseRangeHeaders("bytes=900-5000", total))
    }

    @Test
    fun `a suffix range is the last N bytes, not the first`() {
        // The bug: "bytes=-500" was split on "-" and read positionally, so the empty first
        // half became a null start and 500 became the END — serving bytes 0..500, the
        // opposite end of the file from the one asked for.
        assertEquals(LongRange(500, 999), parseRangeHeaders("bytes=-500", total))
    }

    @Test
    fun `a suffix longer than the file is the whole file`() {
        assertEquals(LongRange(0, 999), parseRangeHeaders("bytes=-5000", total))
    }

    @Test
    fun `a start past the end is unsatisfiable`() {
        // The bug: coerceRange pulled the start back down to the last byte, so this
        // returned byte 999 with a 200 and the client believed it.
        assertNull(parseRangeHeaders("bytes=5000-", total))
        assertNull(parseRangeHeaders("bytes=1000-2000", total))
    }

    @Test
    fun `the last byte is still satisfiable`() {
        assertEquals(LongRange(999, 999), parseRangeHeaders("bytes=999-", total))
    }

    @Test
    fun `an empty file cannot satisfy a range`() {
        assertNull(parseRangeHeaders("bytes=0-", 0))
    }

    @Test
    fun `a zero-length suffix is unsatisfiable`() {
        // "bytes=-0" asks for the last nothing, which has no representation as a range.
        assertNull(parseRangeHeaders("bytes=-0", total))
    }

    @Test
    fun `only the first range of a multi-range request is served`() {
        // Multipart ranges are not implemented. Answering the first is what the old code
        // effectively did with "0-99,200-299" too, except it parsed "99,200" as the end and
        // produced garbage; this at least serves a coherent prefix of what was asked.
        assertEquals(LongRange(0, 99), parseRangeHeaders("bytes=0-99,200-299", total))
    }

    @Test
    fun `whitespace around the bounds is tolerated`() {
        assertEquals(LongRange(100, 199), parseRangeHeaders("bytes= 100 - 199 ", total))
    }
}
