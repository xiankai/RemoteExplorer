package com.bespectacledcyborg.remoteexplorer.data.views

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import com.bespectacledcyborg.remoteexplorer.data.Pairings
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase
import com.bespectacledcyborg.remoteexplorer.data.db.CredentialConverters
import com.bespectacledcyborg.remoteexplorer.data.db.DirTagRelation
import com.bespectacledcyborg.remoteexplorer.data.db.FileTagRelation
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import com.bespectacledcyborg.remoteexplorer.data.db.TagAlias
import com.bespectacledcyborg.remoteexplorer.data.db.ViewTotals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

/** The view facts against a real database: what one open writes, and how groupings add up. */
@RunWith(RobolectricTestRunner::class)
class ViewRecorderTest {

    private lateinit var db: AppDatabase
    private var now = 0L
    private var settings = SettingsEntity()
    private lateinit var recorder: ViewRecorder

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private fun jst(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, tokyo).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
        // FileView cascades from Connection, so the connections have to exist.
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO Connection (id, name, lastModified) VALUES (1, 'nas', 0), (2, 'phone', 0)"
        )
        recorder = ViewRecorder(db.fileViewDao(), { settings }, { now })
    }

    @After
    fun tearDown() = db.close()

    private val beach = FileIdentity(1, "Photos/beach.jpg")

    @Test
    fun `an open counts, stamps both times, and counts its day`() = runTest {
        now = jst(2026, 9, 30, 20)
        recorder.recordOpen(beach)

        val row = db.fileViewDao().get(1, "Photos", "beach.jpg")!!
        assertEquals(1, row.openCount)
        assertEquals(1, row.daysViewed)
        assertEquals(now, row.firstOpenedAt)
        assertEquals(now, row.lastOpenedAt)
        assertEquals(0.0, row.watchedSeconds, 0.0)
    }

    @Test
    fun `a late sitting is one viewing day, and the next starts at noon`() = runTest {
        now = jst(2026, 9, 30, 23); recorder.recordOpen(beach)
        now = jst(2026, 10, 1, 2); recorder.recordOpen(beach)
        now = jst(2026, 10, 1, 11, 59); recorder.recordOpen(beach)
        assertEquals(1, db.fileViewDao().get(1, "Photos", "beach.jpg")!!.daysViewed)

        now = jst(2026, 10, 1, 12); recorder.recordOpen(beach)
        val row = db.fileViewDao().get(1, "Photos", "beach.jpg")!!
        assertEquals(4, row.openCount)
        assertEquals(2, row.daysViewed)
        assertEquals(jst(2026, 9, 30, 23), row.firstOpenedAt)
        assertEquals(jst(2026, 10, 1, 12), row.lastOpenedAt)
    }

    @Test
    fun `a changed boundary applies from the next view without rewriting the count`() = runTest {
        now = jst(2026, 10, 1, 10); recorder.recordOpen(beach) // day 2026-09-30 at noon
        settings = settings.copy(viewingDayStartMinutes = 0) // midnight from now on
        now = jst(2026, 10, 1, 11); recorder.recordOpen(beach) // day 2026-10-01 at midnight
        assertEquals(2, db.fileViewDao().get(1, "Photos", "beach.jpg")!!.daysViewed)
    }

    @Test
    fun `watched time adds up and is kept apart from opens`() = runTest {
        val clip = FileIdentity(1, "Videos/clip.mp4")
        recorder.recordWatched(clip, 12.5)
        recorder.recordWatched(clip, 7.5)

        val row = db.fileViewDao().get(1, "Videos", "clip.mp4")!!
        assertEquals(20.0, row.watchedSeconds, 1e-9)
        assertEquals(0, row.openCount)
        assertNull(row.lastOpenedAt)
    }

    @Test
    fun `a folder is the sum of what is directly inside it`() = runTest {
        now = 1_000; recorder.recordOpen(FileIdentity(1, "Galleries/a/1.jpg"))
        now = 2_000; recorder.recordOpen(FileIdentity(1, "Galleries/a/2.jpg"))
        now = 3_000; recorder.recordOpen(FileIdentity(1, "Galleries/a/2.jpg"))
        // Deeper down is not "directly inside", and does not count towards a.
        now = 9_000; recorder.recordOpen(FileIdentity(1, "Galleries/a/sub/3.jpg"))

        val listing = listOf(
            folder("Galleries", "a"),
            folder("Galleries", "b"),
            file("Galleries", "loose.jpg"),
        )
        val totals = recorder.totalsFor(1, listing, Pairings.NONE)

        // Summed per file, days included: two files each viewed on one day is 2.
        assertEquals(ViewTotals(openCount = 3, daysViewed = 2, lastOpenedAt = 3_000), totals["Galleries/a"])
        // Nothing viewed has no entry, and reads as zero.
        assertNull(totals["Galleries/b"])
        assertNull(totals["Galleries/loose.jpg"])
    }

    @Test
    fun `a paired local copy reads and writes the share's record`() = runTest {
        val pairings = Pairings(listOf(Pairing(2, "Sync/Photos", 1, "Photos")))
        // The viewer canonicalizes before recording, as the view model does.
        recorder.recordOpen(pairings.canonicalize(2, "Sync/Photos/beach.jpg"))
        recorder.recordOpen(beach)

        val totals = recorder.totalsFor(2, listOf(file("Sync/Photos", "beach.jpg", 2)), pairings)
        assertEquals(2, totals["Sync/Photos/beach.jpg"]!!.openCount)
    }

    @Test
    fun `a tag sums its files and its galleries' contents, through the alias group, each once`() = runTest {
        val local = db.localDatabaseDao()
        local.addFileTagRelation(FileTagRelation(1, "Photos", "beach.jpg", "眼鏡"))
        local.addFileTagRelation(FileTagRelation(1, "Photos", "beach.jpg", "female:glasses"))
        local.addDirTagRelation(DirTagRelation(1, "Galleries/a", "female:glasses"))
        local.addTagAliases(
            listOf(
                TagAlias(1, "眼鏡", "accessory/glasses"),
                TagAlias(1, "female:glasses", "accessory/glasses"),
            )
        )
        local.addDirTagRelation(DirTagRelation(1, "Galleries/b", "artist:someone"))

        now = 1_000; recorder.recordOpen(beach)
        now = 2_000; recorder.recordOpen(FileIdentity(1, "Galleries/a/1.jpg"))
        now = 3_000; recorder.recordOpen(FileIdentity(1, "Galleries/b/1.jpg"))
        recorder.recordOpen(FileIdentity(1, "Galleries/b/2.jpg"))

        val totals = recorder.tagTotals()
        assertEquals(2, totals["accessory/glasses"]!!.openCount)
        assertEquals(2_000L, totals["accessory/glasses"]!!.lastOpenedAt)
        assertEquals(2, totals["artist:someone"]!!.openCount)
        assertNull(totals["眼鏡"])
    }

    @Test
    fun `deleting a connection takes its record with it`() = runTest {
        recorder.recordOpen(beach)
        db.openHelper.writableDatabase.execSQL("DELETE FROM Connection WHERE id = 1")
        assertNull(db.fileViewDao().get(1, "Photos", "beach.jpg"))
    }

    private fun folder(parent: String, name: String, connectionId: Int = 1) =
        file(parent, name, connectionId).copy(type = FileType.FOLDER)

    private fun file(parent: String, name: String, connectionId: Int = 1) = LocalFile(
        connectionId = connectionId,
        parentPath = parent,
        filename = name,
        type = FileType.FILE,
        canHaveThumbnail = true,
        lastModified = Date(0),
    )
}
