package com.bespectacledcyborg.remoteexplorer.data

import android.graphics.Bitmap
import android.util.Log

class VideoDecoder {
    companion object {
        private const val TAG = "VideoDecoder"

        // Load the native library
        init {
            System.loadLibrary("ffmpeg")  // Match your CMake project name
        }
    }

    // Track the last decoded frame dimensions
    private var lastDecodedWidth: Int = 0
    private var lastDecodedHeight: Int = 0

    // Decode a video frame from byte array and return bitmap
    fun decodeVideoFrame(videoBytes: ByteArray?): Bitmap? {
        if (videoBytes == null || videoBytes.isEmpty()) {
            Log.e(TAG, "Invalid video data")
            return null
        }

        Log.d(TAG, "Decoding video frame from ${videoBytes.size} bytes")

        // Call native method to decode frame
        val bitmapData = decodeVideoFrameNative(videoBytes) ?: run {
            Log.e(TAG, "Failed to decode video frame")
            return null
        }

        // Get dimensions from native code
        lastDecodedWidth = getLastDecodedWidth()
        lastDecodedHeight = getLastDecodedHeight()

        if (lastDecodedWidth <= 0 || lastDecodedHeight <= 0) {
            // If we don't have explicit dimensions, try to infer from data
            // A crude estimate: assuming RGBA (4 bytes per pixel)
            val pixels = bitmapData.size / 4
            lastDecodedWidth = kotlin.math.sqrt(pixels.toDouble()).toInt()
            lastDecodedHeight = lastDecodedWidth
            Log.w(TAG, "Using estimated dimensions: ${lastDecodedWidth}x${lastDecodedHeight}")
        }

        try {
            // Create bitmap from RGBA data
            val bitmap = Bitmap.createBitmap(
                lastDecodedWidth,
                lastDecodedHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bitmapData))

            Log.d(TAG, "Created bitmap: ${lastDecodedWidth}x${lastDecodedHeight}")
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating bitmap", e)
            return null
        }
    }

    // Native methods
    private external fun decodeVideoFrameNative(videoBytes: ByteArray): ByteArray?
    private external fun getLastDecodedWidth(): Int
    private external fun getLastDecodedHeight(): Int
}
