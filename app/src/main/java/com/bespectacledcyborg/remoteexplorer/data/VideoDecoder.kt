package com.bespectacledcyborg.remoteexplorer.data

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap

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

    // Decode a video frame from byte array and return bitmap
    fun decodeVideoFrame(videoBytes: ByteArray?): Bitmap? {
        if (videoBytes == null || videoBytes.isEmpty()) {
            Log.e(TAG, "Invalid video data")
            return null
        }

        Log.d(TAG, "Decoding video frame from ${videoBytes.size} bytes")

        // Call native method to decode frame
        val combinedData = decodeVideoFrameNative(videoBytes) ?: run {
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
    // followed by the actual RGBA bitmap data
    private external fun decodeVideoFrameNative(videoBytes: ByteArray): ByteArray?
}
