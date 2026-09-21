package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifThumbnailDirectory
import java.io.ByteArrayInputStream

/**
 * Pulls the thumbnail a camera already wrote into a photo out of the file's header.
 *
 * This is the whole point of the fast path: a JPEG segment's length is a 16-bit field, so the
 * APP1 block holding the thumbnail can never start beyond the first few tens of KB. A grid
 * cell therefore costs one short ranged read rather than transferring a multi-megabyte photo
 * to throw almost all of it away.
 *
 * Returns null when there is no embedded thumbnail, when [header] was cut before the end of
 * it, or when the bytes do not look like a JPEG — all ordinary, and all meaning "fall back to
 * decoding the image itself".
 */
internal fun extractEmbeddedThumbnail(header: ByteArray): ByteArray? {
    val exifStart = findExifApp1Payload(header) ?: return null
    val metadata = readMetadata(header) ?: return null

    val directory = metadata.getFirstDirectoryOfType(ExifThumbnailDirectory::class.java)
        ?: return null

    // getAdjustedThumbnailOffset is relative to the start of the APP1 payload, NOT to the
    // file. Slicing the file at it yields whatever happens to be there — bytes that are not
    // a JPEG but are still the right length, so nothing downstream notices until the image
    // fails to decode. The payload's own position has to be added back.
    val offset = directory.getAdjustedThumbnailOffset() ?: return null
    val length = directory.getInteger(ExifThumbnailDirectory.TAG_THUMBNAIL_LENGTH) ?: return null

    val start = exifStart + offset
    if (length <= 0 || start < 0 || start + length > header.size) return null

    val jpeg = header.copyOfRange(start, start + length)
    return jpeg.takeIf { looksLikeJpeg(it) }
}

/**
 * How far the photo has to be turned to be the right way up, in degrees clockwise.
 *
 * Read separately from the thumbnail because it applies to *both* ways of producing one. An
 * embedded thumbnail is stored in the same orientation as the full image, and BitmapFactory
 * does not rotate what it decodes either — so applying this on only one of the two paths
 * would make a thumbnail's orientation depend on whether the camera happened to embed one.
 */
internal fun exifOrientationDegrees(header: ByteArray): Int {
    val orientation = readMetadata(header)
        ?.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)

    return rotationForOrientation(orientation)
}

/** A header cut mid-segment throws rather than returning nothing. */
private fun readMetadata(header: ByteArray): Metadata? = try {
    ImageMetadataReader.readMetadata(ByteArrayInputStream(header))
} catch (e: Exception) {
    null
}

/**
 * Walks the JPEG segment markers to find where the EXIF APP1 payload begins.
 *
 * The `Exif\u0000\u0000` signature is checked rather than taking the first APP1: XMP is also
 * carried in an APP1 segment, and in this app in particular — it is built around XMP sidecars
 * — a photo with embedded XMP is entirely likely. Taking that one would put the payload start
 * in the wrong place and quietly produce a corrupt thumbnail.
 */
private fun findExifApp1Payload(header: ByteArray): Int? {
    if (header.size < 4 || header.u8(0) != 0xFF || header.u8(1) != 0xD8) return null

    var pos = 2
    while (pos + 4 <= header.size) {
        if (header.u8(pos) != 0xFF) return null

        val marker = header.u8(pos + 1)
        // Start of scan: image data begins, so every header segment is behind us.
        if (marker == 0xDA) return null

        val segmentLength = (header.u8(pos + 2) shl 8) or header.u8(pos + 3)
        if (segmentLength < 2) return null

        val payload = pos + 4
        if (marker == 0xE1 && payload + EXIF_SIGNATURE.size <= header.size) {
            val isExif = EXIF_SIGNATURE.indices.all {
                header[payload + it] == EXIF_SIGNATURE[it]
            }
            if (isExif) return payload
        }

        pos += 2 + segmentLength
    }
    return null
}

private fun looksLikeJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8 &&
        bytes.u8(bytes.size - 2) == 0xFF && bytes.u8(bytes.size - 1) == 0xD9

/**
 * EXIF orientation as degrees of clockwise rotation.
 *
 * Only the four rotations are honoured; the mirrored orientations (2, 4, 5, 7) are vanishingly
 * rare and are treated as their unmirrored rotation rather than left unhandled.
 */
internal fun rotationForOrientation(orientation: Int?): Int = when (orientation) {
    3, 4 -> 180
    5, 6 -> 90
    7, 8 -> 270
    else -> 0
}

private val EXIF_SIGNATURE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\u0000\u0000"

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
