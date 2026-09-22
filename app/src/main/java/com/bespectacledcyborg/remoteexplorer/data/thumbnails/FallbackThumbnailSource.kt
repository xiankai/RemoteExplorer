package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import android.util.Log
import java.io.File

/**
 * Tries each source in turn, taking the first that produces a thumbnail.
 *
 * The two video decoders turned out to be complementary rather than one dominating, so the
 * union of them beats either alone (#41, measured on the Codecs fixture):
 *
 * |            | h264 | hevc | vp9 | av1 | theora | mpeg4 asp |
 * |------------|------|------|-----|-----|--------|-----------|
 * | platform   |  ok  |  ok  | ok  | ok  |   no   |    no     |
 * | ffmpeg     |  ok  |  ok  | ok  | no  |   ok   |    ok     |
 *
 * Order matters and is not arbitrary: the platform extractor goes first because it is
 * cheaper on every format both can handle — it seeks rather than reading a prefix, so the
 * benchmark folder costs 19 MiB against 66 MiB — and because it is the only one that can
 * read a file whose `moov` atom sits at the end. FFmpeg then covers the older formats the
 * device has no codec for.
 *
 * Only "cannot decode" falls through. A source that throws has hit the share rather than
 * the file, and trying the next one would just fail again more slowly and bury the cause.
 */
class FallbackThumbnailSource(
    private val sources: List<ThumbnailSource>,
) : ThumbnailSource {

    override suspend fun render(connectionId: Int, path: String, destination: File): Boolean {
        for ((index, source) in sources.withIndex()) {
            if (source.render(connectionId, path, destination)) {
                if (index > 0) {
                    Log.d(TAG, "Fell back to ${source.javaClass.simpleName} for $path")
                }
                return true
            }
        }
        return false
    }

    private companion object {
        const val TAG = "FallbackThumbnailSource"
    }
}
