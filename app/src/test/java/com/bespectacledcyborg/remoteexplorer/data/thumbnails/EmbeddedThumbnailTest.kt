package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Covers locating the thumbnail a camera embedded in a photo's header.
 *
 * Worth testing rather than eyeballing, because every way of getting it wrong is silent: the
 * offsets still point *somewhere*, and the wrong slice is bytes of the right length that
 * simply are not an image. The JPEGs here are assembled byte by byte so the expected answer
 * is known exactly.
 */
class EmbeddedThumbnailTest {

    private val thumbnailJpeg = jpegBody(marker = 0x42)

    @Test
    fun `finds the embedded thumbnail`() {
        val file = jpegWithExifThumbnail(thumbnailJpeg)

        val found = extractEmbeddedThumbnail(file)

        assertArrayEquals(thumbnailJpeg, found)
    }

    @Test
    fun `skips an XMP APP1 to find the EXIF one`() {
        // XMP also travels in an APP1 segment, and this app is built around XMP sidecars, so
        // a photo carrying both is entirely ordinary. Taking the first APP1 rather than the
        // one signed "Exif\u0000\u0000" puts the payload start in the wrong place and slices
        // out bytes that are the right length and not a JPEG.
        val file = jpegWithExifThumbnail(thumbnailJpeg, precedingXmp = true)

        val found = extractEmbeddedThumbnail(file)

        assertArrayEquals(thumbnailJpeg, found)
    }

    @Test
    fun `reads the orientation from the same header`() {
        // 6 is a quarter turn clockwise, which is how a camera records "held on its side".
        // An embedded thumbnail is stored the same way up as the full image, so ignoring
        // this shows portrait photographs lying down.
        val file = jpegWithExifThumbnail(thumbnailJpeg, orientation = 6)

        assertEquals(90, exifOrientationDegrees(file))
    }

    @Test
    fun `a photo with no orientation tag is left alone`() {
        assertEquals(0, exifOrientationDegrees(jpegBody(marker = 0x11)))
        assertEquals(0, exifOrientationDegrees("not an image".toByteArray()))
    }

    @Test
    fun `gives up when the header stops before the thumbnail does`() {
        // The caller reads a fixed slice off the share. A photo whose APP1 sits unusually
        // late must fall back to decoding the image rather than return a truncated JPEG.
        val file = jpegWithExifThumbnail(thumbnailJpeg)

        assertNull(extractEmbeddedThumbnail(file.copyOf(file.size / 2)))
    }

    @Test
    fun `returns nothing for a JPEG with no embedded thumbnail`() {
        val file = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            write(jpegBody(marker = 0x11))
        }.toByteArray()

        assertNull(extractEmbeddedThumbnail(file))
    }

    @Test
    fun `returns nothing for something that is not a JPEG`() {
        assertNull(extractEmbeddedThumbnail("not an image at all".toByteArray()))
        assertNull(extractEmbeddedThumbnail(ByteArray(0)))
    }

    @Test
    fun `maps every EXIF orientation to a rotation`() {
        assertEquals(0, rotationForOrientation(1))
        assertEquals(180, rotationForOrientation(3))
        assertEquals(90, rotationForOrientation(6))
        assertEquals(270, rotationForOrientation(8))
        // Absent or unrecognised means leave it alone rather than guess.
        assertEquals(0, rotationForOrientation(null))
        assertEquals(0, rotationForOrientation(99))
    }

    // --- building JPEGs by hand ----------------------------------------------------------

    /** A minimal but structurally valid JPEG: SOI, a comment segment, EOI. */
    private fun jpegBody(marker: Int): ByteArray {
        val payload = ByteArray(64) { marker.toByte() }
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // COM
            writeU16(payload.size + 2)
            write(payload)
            write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        }.toByteArray()
    }

    /**
     * Assembles a JPEG whose APP1 carries a TIFF block with IFD0 (orientation) and IFD1
     * (the thumbnail's offset and length), mirroring what a camera writes.
     */
    private fun jpegWithExifThumbnail(
        thumbnail: ByteArray,
        orientation: Int = 1,
        precedingXmp: Boolean = false,
    ): ByteArray {
        val ifd0 = ByteArrayOutputStream().apply {
            writeU16(1)
            write(ifdEntry(tag = 0x0112, type = 3, value = orientation))
        }.toByteArray()

        val ifd0End = 8 + ifd0.size + 4 // TIFF header, IFD0, then the pointer to IFD1
        val ifd1Size = 2 + 12 * 2 + 4
        val thumbnailOffset = ifd0End + ifd1Size

        val tiff = ByteArrayOutputStream().apply {
            write("MM".toByteArray())
            writeU16(42)
            writeU32(8) // IFD0 sits immediately after the header
            write(ifd0)
            writeU32(ifd0End) // IFD1 follows IFD0
            writeU16(2)
            write(ifdEntry(tag = 0x0201, type = 4, value = thumbnailOffset))
            write(ifdEntry(tag = 0x0202, type = 4, value = thumbnail.size))
            writeU32(0) // no further IFD
            write(thumbnail)
        }.toByteArray()

        val exifPayload = EXIF_SIGNATURE_BYTES + tiff

        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            if (precedingXmp) {
                val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray()
                write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
                writeU16(xmp.size + 2)
                write(xmp)
            }
            write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
            writeU16(exifPayload.size + 2)
            write(exifPayload)
            write(jpegBody(marker = 0x77).drop(2).toByteArray()) // body without its own SOI
        }.toByteArray()
    }

    /** One 12-byte IFD entry. SHORT values are left-justified in the value field. */
    private fun ifdEntry(tag: Int, type: Int, value: Int): ByteArray =
        ByteArrayOutputStream().apply {
            writeU16(tag)
            writeU16(type)
            writeU32(1) // count
            if (type == 3) {
                writeU16(value)
                writeU16(0)
            } else {
                writeU32(value)
            }
        }.toByteArray()

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private companion object {
        val EXIF_SIGNATURE_BYTES = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
    }
}
