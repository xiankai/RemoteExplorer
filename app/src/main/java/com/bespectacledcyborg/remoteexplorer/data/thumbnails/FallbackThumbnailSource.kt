package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import android.util.Log
import java.io.File
import java.io.FileOutputStream

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
            // Every source shares one destination, so hand the next one an empty file
            // rather than whatever the last one left behind. Nothing writes before
            // returning false today — both encoders write once, at the end, through a
            // truncating stream — but that is an invariant of the implementations rather
            // than of the interface, and a source acquiring a write-then-give-up path
            // later would corrupt the next one's output in a way that is very hard to
            // read back from a broken thumbnail.
            if (destination.length() > 0L) {
                FileOutputStream(destination).close()
            }
        }
        return false
    }

    private companion object {
        const val TAG = "FallbackThumbnailSource"
    }
}
