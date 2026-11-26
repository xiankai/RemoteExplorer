package com.bespectacledcyborg.remoteexplorer.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class VideoViewerEntryTest {

    @Test
    fun `a video file has a viewer page`() {
        assertTrue(entry("clip.mp4").hasVideoViewer())
        assertTrue(entry("old.avi").hasVideoViewer())
        assertTrue(entry("CLIP.MKV").hasVideoViewer())
    }

    @Test
    fun `an image, audio or other file has none`() {
        assertFalse(entry("photo.jpg").hasVideoViewer())
        assertFalse(entry("song.mp3").hasVideoViewer())
        assertFalse(entry("notes.txt").hasVideoViewer())
    }

    @Test
    fun `a folder named like a video has none`() {
        assertFalse(entry("clip.mp4", FileType.FOLDER).hasVideoViewer())
    }

    private fun entry(name: String, type: FileType = FileType.FILE) = LocalFile(
        connectionId = 1,
        parentPath = "Videos",
        filename = name,
        type = type,
        canHaveThumbnail = true,
        lastModified = Date(0),
    )
}
