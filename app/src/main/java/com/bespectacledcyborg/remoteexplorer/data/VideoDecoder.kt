package com.bespectacledcyborg.remoteexplorer.data

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_SIZE
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.RemoteRandomAccess

class VideoDecoder {
    companion object {
        private const val TAG = "VideoDecoder"

        // Load the native library
        init {
            System.loadLibrary("ffmpeg") // Match your CMake project name
        }
    }

    // Track the last decoded frame dimensions
    private var lastDecodedWidth: Int = 0
    private var lastDecodedHeight: Int = 0

    /**
     * Decodes the first frame of the file behind [reader], or null when there is none.
     *
     * [reader] replaces the byte array this used to take. A prefix of a file cannot answer
     * for the whole of it: the demuxer was told the video ended where the prefix did, so
     * anything with its `moov` atom at the end was unreachable and the caller answered by
     * reading another 2 MB and re-probing the whole accumulated buffer — nine times, for
     * 18 MB and no frame. Now FFmpeg seeks, and reads only what it seeks to.
     */
    fun decodeVideoFrame(reader: RemoteRandomAccess): Bitmap? {
        // Call native method to decode frame, already scaled to the thumbnail box. The
        // native side used to hand back the frame at full resolution — a 4K frame is about
        // 33 MB of RGBA — which crossed JNI, was copied again below, and was then thrown
        // away at 128 px. sws_scale has to run anyway for the colour conversion, so giving
        // it the target size costs nothing and shrinks everything after it.
        val combinedData = decodeVideoFrameNative(reader, THUMBNAIL_SIZE) ?: run {
            Log.e(TAG, "Failed to decode video frame")
            return null
        }

        // The first 8 bytes contain width (4 bytes) and height (4 bytes)
        if (combinedData.size < 8) {
            Log.e(TAG, "Invalid data format: too small to contain dimensions")
            return null
        }

        // Extract width from first 4 bytes (big-endian)
        val width = ((combinedData[0].toInt() and 0xFF) shl 24) or
                ((combinedData[1].toInt() and 0xFF) shl 16) or
                ((combinedData[2].toInt() and 0xFF) shl 8) or
                (combinedData[3].toInt() and 0xFF)

        // Extract height from next 4 bytes (big-endian)
        val height = ((combinedData[4].toInt() and 0xFF) shl 24) or
                ((combinedData[5].toInt() and 0xFF) shl 16) or
                ((combinedData[6].toInt() and 0xFF) shl 8) or
                (combinedData[7].toInt() and 0xFF)

        // Store dimensions for potential future use
        lastDecodedWidth = width
        lastDecodedHeight = height

        Log.d(TAG, "Decoded frame dimensions: ${width}x${height}")

        // The actual bitmap data starts after the first 8 bytes
        val bitmapData = combinedData.copyOfRange(8, combinedData.size)

        try {
            // Create bitmap from RGBA data
            val bitmap = createBitmap(width, height)
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bitmapData))

            Log.d(TAG, "Created bitmap: ${width}x${height}")
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating bitmap", e)
            return null
        }
    }

    // Native method - returns byte array with width (4 bytes) and height (4 bytes)
    // followed by the actual RGBA bitmap data, scaled so its longest edge is at most
    // maxSize. The bound is passed in rather than duplicated in C, so THUMBNAIL_SIZE has
    // one definition; a frame already inside the box comes back untouched.
    //
    // The C side calls back into `reader` by name — readAt(J[BII)I and size()J — so the
    // two stay in step by convention rather than by the compiler. See ffmpeg_wrapper.c.
    private external fun decodeVideoFrameNative(
        reader: RemoteRandomAccess,
        maxSize: Int,
    ): ByteArray?
}
