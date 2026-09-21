package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers how far down a photo is decoded on the fallback path.
 *
 * The old code computed this against a bounds object it never ran a bounds pass into, so
 * `outWidth`/`outHeight` were 0, the branch never fired and the answer was always 1 — a 24 MP
 * photo decoded whole, roughly 96 MB of ARGB_8888, purely to be scaled down to 128 px.
 * Getting it wrong costs memory rather than correctness, which is why it went unnoticed.
 */
class SampleSizeTest {

    @Test
    fun `an image already at the target is not sampled down`() {
        assertEquals(1, sampleSizeFor(width = 128, height = 128, target = 128))
        assertEquals(1, sampleSizeFor(width = 200, height = 150, target = 128))
    }

    @Test
    fun `a large photo is decoded at a fraction of its size`() {
        // 6000x4000, the shorter side 4000: halving stops while it stays >= 128.
        assertEquals(16, sampleSizeFor(width = 6000, height = 4000, target = 128))
    }

    @Test
    fun `sampling never takes the shorter side below the target`() {
        // Stopping a step late would mean upscaling a too-small bitmap back up, which looks
        // worse than decoding a little more than needed.
        listOf(300, 500, 1000, 4000, 12000).forEach { size ->
            val sampled = size / sampleSizeFor(size, size, 128)
            assert(sampled >= 128) { "$size sampled down to $sampled, below the 128 target" }
        }
    }

    @Test
    fun `the result is always a power of two`() {
        // BitmapFactory rounds inSampleSize down to one anyway, so anything else silently
        // decodes larger than asked.
        listOf(129, 260, 700, 3000, 9000).forEach { size ->
            val sampleSize = sampleSizeFor(size, size, 128)
            assertEquals(
                "sample size $sampleSize for $size is not a power of two",
                0,
                sampleSize and (sampleSize - 1),
            )
        }
    }

    @Test
    fun `degenerate bounds fall back to decoding at full size`() {
        // What a failed bounds pass looks like. Returning 1 renders correctly; returning 0
        // or a negative would be a crash inside BitmapFactory.
        assertEquals(1, sampleSizeFor(width = 0, height = 0, target = 128))
        assertEquals(1, sampleSizeFor(width = -1, height = 100, target = 128))
        assertEquals(1, sampleSizeFor(width = 100, height = 100, target = 0))
    }
}
