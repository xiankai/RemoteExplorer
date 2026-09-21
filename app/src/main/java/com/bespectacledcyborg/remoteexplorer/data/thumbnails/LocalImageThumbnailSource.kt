package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_SIZE
import com.bespectacledcyborg.remoteexplorer.data.tasks.resizeBitmap
import java.io.File

/**
 * How much of a photo's head to read when looking for an embedded thumbnail.
 *
 * A JPEG segment carries a 16-bit length, so an APP1 block cannot exceed 64 KB and cannot
 * begin beyond the handful of segments before it. 128 KB covers the block wherever it sits,
 * with room for a JFIF or ICC segment ahead of it.
 */
private const val HEADER_BYTES = 128 * 1024

/**
 * Renders image thumbnails on the device, reading as little of the file as it can.
 *
 * Nothing here writes to the share: the embedded thumbnail is a ranged *read* of the header,
 * and the fallback streams the file without opening it for write.
 */
class LocalImageThumbnailSource(
    private val sambaFileDataSource: SambaFileDataSource,
) : ThumbnailSource {

    override suspend fun render(connectionId: Int, path: String, destination: File): Boolean {
        val header = readHeader(connectionId, path)

        // Orientation governs both paths. A camera stores the embedded thumbnail the same
        // way up as the full image, and BitmapFactory does not rotate what it decodes
        // either, so reading it once here is what stops a photo's thumbnail coming out
        // sideways on one path and upright on the other.
        val rotation = header?.let { exifOrientationDegrees(it) } ?: 0

        header?.let(::extractEmbeddedThumbnail)?.let { jpeg ->
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            if (bitmap != null) {
                Log.d(TAG, "Served $path from its embedded thumbnail (${jpeg.size} bytes)")
                resizeBitmap(bitmap.rotated(rotation), destination)
                return true
            }
            // Present but undecodable. Fall through and do it the expensive way.
            Log.d(TAG, "Embedded thumbnail for $path would not decode")
        }

        return decodeDownsampled(connectionId, path, destination, rotation, header)
    }

    /**
     * Reads the head of the file, which is where everything cheap lives.
     *
     * A ranged read at offset 0 rather than a stream: nothing beyond the header is wanted,
     * and a short file simply returns fewer bytes.
     */
    private fun readHeader(connectionId: Int, path: String): ByteArray? =
        sambaFileDataSource.openFile(connectionId, path).use { handle ->
            val header = ByteArray(HEADER_BYTES)
            val read = handle.read(header, 0L, 0, HEADER_BYTES)
            when {
                read <= 0 -> null
                read == HEADER_BYTES -> header
                else -> header.copyOf(read)
            }
        }

    /**
     * The fallback: stream the file once, decoding at a fraction of full size.
     *
     * The bounds pass is what makes this affordable. The old worker set
     * `inJustDecodeBounds` on an Options object it then never used for a bounds decode, so
     * `outWidth`/`outHeight` stayed 0, `inSampleSize` was always 1, and a 24 MP photo was
     * decoded whole — around 96 MB of ARGB_8888 — purely to be scaled down to 128 px.
     */
    private fun decodeDownsampled(
        connectionId: Int,
        path: String,
        destination: File,
        rotation: Int,
        header: ByteArray?,
    ): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        // The dimensions live in the SOF marker near the front of the file, so the header
        // already in hand almost always answers this — no point fetching those bytes twice.
        if (header != null) {
            BitmapFactory.decodeByteArray(header, 0, header.size, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            sambaFileDataSource.openFile(connectionId, path).use { handle ->
                BitmapFactory.decodeStream(handle.inputStream, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.d(TAG, "Could not read image bounds for $path")
            return false
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, THUMBNAIL_SIZE)
        }
        // The whole file this time: there is no embedded thumbnail to lift, so the pixels
        // have to come off the wire. inSampleSize keeps the *decode* cheap, not the transfer
        // — that is the cost the embedded path above exists to avoid.
        val bitmap = sambaFileDataSource.openFile(connectionId, path).use { handle ->
            BitmapFactory.decodeStream(handle.inputStream, null, options)
        } ?: return false

        resizeBitmap(bitmap.rotated(rotation), destination)
        return true
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (rotated != this) recycle()
        return rotated
    }

    private companion object {
        const val TAG = "LocalImageThumbnailSource"
    }
}

/**
 * The power of two to decode at, so the result is the smallest one still at least [target] on
 * its shorter side.
 *
 * Halving stops one step early on purpose: going past the target would mean upscaling a
 * too-small bitmap back up to fill the thumbnail, which looks worse than decoding slightly
 * more than needed.
 */
internal fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0 || target <= 0) return 1

    var sampleSize = 1
    while (minOf(width, height) / (sampleSize * 2) >= target) {
        sampleSize *= 2
    }
    return sampleSize
}
