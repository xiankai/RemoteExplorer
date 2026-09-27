package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

/**
 * Tags that belong to a gallery rather than to a file, against a real database.
 *
 * The queries are the point: a count that multiplies one relation by the other, or a listing
 * that returns a different kind of thing depending on where the user has been, is invisible
 * in a unit test of the code around it and obvious here.
 */
@RunWith(RobolectricTestRunner::class)
class TagIndexDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LocalDatabaseDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
        dao = db.localDatabaseDao()
    }

    @After
    fun tearDown() = db.close()

    private fun file(parentPath: String, filename: String) = LocalFile(
        connectionId = 1,
        parentPath = parentPath,
        filename = filename,
        type = FileType.FILE,
        canHaveThumbnail = true,
        lastModified = Date(0),
    )

    /**
     * Seeds a listing. Grouped by directory because `reconcileDirectory` replaces a
     * directory's contents — calling it once per file leaves only the last one.
     */
    private fun given(vararg files: LocalFile) =
        files.groupBy { it.parentPath }.forEach { (parentPath, inDirectory) ->
            dao.reconcileDirectory(1, parentPath, inDirectory, Date(0))
        }

    /**
     * A directory tag names the gallery, and only the gallery.
     *
     * Expanding it to the files inside would make the same tag list different kinds of thing
     * depending on which galleries happened to be cached — contents where someone had
     * browsed, folders where they had not.
     */
    @Test
    fun `a directory tag surfaces the gallery, not its contents`() {
        given(file("g/gallery", "001.jpg"), file("g/gallery", "002.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("artist:someone"), deleted = false)

        assertTrue(dao.getFilesWithTag("artist:someone").isEmpty())
        assertEquals(
            listOf("g/gallery"),
            dao.getDirectoriesWithTag("artist:someone").map { it.parentPath },
        )
    }

    /**
     * The gallery is findable with nothing cached inside it, which is the whole point: the
     * listing cache only knows directories somebody has opened, so on a fresh install it has
     * nothing to say about the entire index.
     */
    @Test
    fun `a tagged gallery is findable before anyone browses it`() {
        dao.applyDirectoryTags(1, "g/unvisited", listOf("t"), deleted = false)

        assertEquals(listOf("g/unvisited"), dao.getDirectoriesWithTag("t").map { it.parentPath })
    }

    @Test
    fun `a sidecar tag surfaces the file it named`() {
        given(file("photos", "beach.jpg"), file("photos", "city.jpg"))
        dao.applyFileTags(1, "photos", "beach.jpg", listOf("holiday"), deleted = false)

        assertEquals(listOf("beach.jpg"), dao.getFilesWithTag("holiday").map { it.filename })
    }

    /**
     * The count adds the two relations rather than joining them. Joined, a tag on three
     * files inside a gallery already carrying it reports nine.
     */
    @Test
    fun `counts add the two relations instead of multiplying them`() {
        given(file("g/gallery", "001.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("t"), deleted = false)
        dao.applyFileTags(1, "elsewhere", "loose.jpg", listOf("t"), deleted = false)

        // One gallery plus one file: two rows to show, and two is what it says.
        assertEquals(2, dao.getTags().single { it.tag == "t" }.count)
    }

    /**
     * The count is what opening the tag will show, so a gallery counts once however many
     * files it holds — and counts at all before anyone has browsed into it.
     */
    @Test
    fun `a gallery counts once, browsed or not`() {
        dao.applyDirectoryTags(1, "g/unvisited", listOf("t"), deleted = false)

        assertEquals(1, dao.getTags().single { it.tag == "t" }.count)
    }

    @Test
    fun `a tagged gallery renders as a folder in its parent`() {
        val folder = DirTagRelation(1, "gallery-dl/pixiv/[137128361] 羽衣狐", "t").asFolder()

        assertEquals("gallery-dl/pixiv", folder.parentPath)
        assertEquals("[137128361] 羽衣狐", folder.filename)
        assertEquals(FileType.FOLDER, folder.type)
        assertEquals("gallery-dl/pixiv/[137128361] 羽衣狐", folder.fullPath())
    }

    /**
     * A re-appended record replaces rather than adds. The log is append-only and the last
     * record for a path wins, so accumulating would keep every tag a gallery ever had and a
     * tag removed upstream would never disappear.
     */
    @Test
    fun `re-applying a gallery replaces its tags`() {
        dao.applyDirectoryTags(1, "g/gallery", listOf("old", "kept"), deleted = false)
        dao.applyDirectoryTags(1, "g/gallery", listOf("kept", "new"), deleted = false)

        assertTrue(dao.getDirectoriesWithTag("old").isEmpty())
        assertEquals(1, dao.getDirectoriesWithTag("kept").size)
        assertEquals(1, dao.getDirectoriesWithTag("new").size)
    }

    @Test
    fun `a tombstone drops the gallery's tags`() {
        dao.applyDirectoryTags(1, "g/gallery", listOf("t"), deleted = false)
        dao.applyDirectoryTags(1, "g/gallery", emptyList(), deleted = true)

        assertTrue(dao.getDirectoriesWithTag("t").isEmpty())
        assertEquals(0, dao.getTags().single { it.tag == "t" }.count)
    }

    /**
     * A full pull replaces what a connection ingested, because the log it is reading is a
     * different one — a path that has since lost its tags has to lose them here too.
     * Sidecar-derived rows are not the index's to remove.
     */
    @Test
    fun `clearing ingested tags leaves sidecar tags alone`() {
        given(file("photos", "beach.jpg"))
        dao.applyDirectoryTags(1, "g/gallery", listOf("from-index"), deleted = false)
        dao.applyFileTags(1, "photos", "beach.jpg", listOf("from-sidecar"), deleted = false)

        dao.clearIngestedTags(1)

        assertTrue(dao.getDirectoriesWithTag("from-index").isEmpty())
        assertEquals(listOf("beach.jpg"), dao.getFilesWithTag("from-sidecar").map { it.filename })
    }

    @Test
    fun `clearing one connection leaves another alone`() {
        dao.applyDirectoryTags(1, "g/one", listOf("t"), deleted = false)
        dao.applyDirectoryTags(2, "g/two", listOf("t"), deleted = false)

        dao.clearIngestedTags(1)

        assertEquals(listOf("g/two"), dao.getDirectoriesWithTag("t").map { it.parentPath })
    }

    @Test
    fun `the ingest offset round trips and is per connection`() {
        assertNull(dao.getTagIndexIngest(1))

        dao.upsertTagIndexIngest(TagIndexIngest(1, "gen-2", 773_000L))
        dao.upsertTagIndexIngest(TagIndexIngest(2, "other", 10L))
        dao.upsertTagIndexIngest(TagIndexIngest(1, "gen-2", 900_000L))

        assertEquals(900_000L, dao.getTagIndexIngest(1)?.bytesRead)
        assertEquals("gen-2", dao.getTagIndexIngest(1)?.generation)
        assertEquals(10L, dao.getTagIndexIngest(2)?.bytesRead)
    }

    /** A pass lands as one transaction: the rows and the offset that describes them. */
    @Test
    fun `applying a pass stores the rows and the offset together`() {
        given(file("photos", "beach.jpg"))

        dao.applyTagIndex(
            connectionId = 1,
            replace = true,
            directories = listOf(DirectoryTagUpdate("g/gallery", listOf("t"), deleted = false)),
            files = listOf(FileTagUpdate("photos", "beach.jpg", listOf("s"), deleted = false)),
            generation = "gen-2",
            bytesRead = 42L,
        )

        assertEquals(listOf("g/gallery"), dao.getDirectoriesWithTag("t").map { it.parentPath })
        assertEquals(listOf("beach.jpg"), dao.getFilesWithTag("s").map { it.filename })
        assertEquals(42L, dao.getTagIndexIngest(1)?.bytesRead)
    }

    // ---- the alias layer ----------------------------------------------------------------

    private fun aliases(connectionId: Int, canonical: String, vararg members: String) =
        members.map { TagAlias(connectionId, it, canonical) }

    /**
     * The case the layer exists for, with the real spellings: pixiv says `眼鏡`, examplebooru
     * says `female:glasses`, and either one should find both galleries.
     */
    @Test
    fun `a tag finds galleries carrying any member of its group`() {
        dao.applyDirectoryTags(1, "gallery-dl/pixiv/1", listOf("眼鏡"), deleted = false)
        dao.applyDirectoryTags(1, "gallery-dl/examplebooru/2", listOf("female:glasses"), deleted = false)
        dao.applyDirectoryTags(1, "gallery-dl/examplebooru/3", listOf("female:ponytail"), deleted = false)
        dao.replaceTagAliases(
            1, aliases(1, "female/glasses", "glasses", "female:glasses", "眼鏡"), "7",
        )

        val expected = listOf("gallery-dl/examplebooru/2", "gallery-dl/pixiv/1")
        assertEquals(expected, dao.getDirectoriesWithTag("眼鏡").map { it.parentPath })
        assertEquals(expected, dao.getDirectoriesWithTag("female:glasses").map { it.parentPath })
        // The bare leaf is a member too, and carried by nothing — it still widens.
        assertEquals(expected, dao.getDirectoriesWithTag("glasses").map { it.parentPath })
    }

    @Test
    fun `a tag in no group finds only itself`() {
        dao.applyDirectoryTags(1, "g/a", listOf("solo"), deleted = false)
        dao.replaceTagAliases(1, aliases(1, "x/other", "other", "another"), "1")

        assertEquals(listOf("g/a"), dao.getDirectoriesWithTag("solo").map { it.parentPath })
    }

    /** Two members of one group on the same gallery are still one gallery. */
    @Test
    fun `a gallery carrying two members is listed once`() {
        dao.applyDirectoryTags(1, "g/work", listOf("眼鏡", "丸眼鏡"), deleted = false)
        dao.replaceTagAliases(1, aliases(1, "female/glasses", "眼鏡", "丸眼鏡"), "1")

        assertEquals(listOf("g/work"), dao.getDirectoriesWithTag("眼鏡").map { it.parentPath })
    }

    @Test
    fun `sidecar-tagged files widen the same way, once each`() {
        given(file("photos", "a.jpg"), file("photos", "b.jpg"))
        dao.addFileTagRelation(FileTagRelation(1, "photos", "a.jpg", "TT"))
        dao.addFileTagRelation(FileTagRelation(1, "photos", "a.jpg", "twintails"))
        dao.addFileTagRelation(FileTagRelation(1, "photos", "b.jpg", "ツインテール"))
        dao.replaceTagAliases(1, aliases(1, "female/twintails", "TT", "twintails", "ツインテール"), "1")

        assertEquals(
            listOf("a.jpg", "b.jpg"),
            dao.getFilesWithTag("twintails").map { it.filename }.sorted(),
        )
    }

    /**
     * One hop only. `a` and `c` share nothing but a member of a member's group; walking
     * further would be the transitive merge the generator refuses to make.
     */
    @Test
    fun `does not chain through a member that sits in two groups`() {
        dao.applyDirectoryTags(1, "g/a", listOf("a"), deleted = false)
        dao.applyDirectoryTags(1, "g/c", listOf("c"), deleted = false)
        dao.replaceTagAliases(1, aliases(1, "x/ab", "a", "b") + aliases(1, "x/bc", "b", "c"), "1")

        assertEquals(listOf("g/a"), dao.getDirectoriesWithTag("a").map { it.parentPath })
        // b sits in both, so it reaches both — which is what being in both groups means.
        assertEquals(listOf("g/a", "g/c"), dao.getDirectoriesWithTag("b").map { it.parentPath })
    }

    /** A new generation replaces: a group corrected away upstream must stop widening. */
    @Test
    fun `a new alias generation replaces the old groups`() {
        dao.applyDirectoryTags(1, "g/pantyhose", listOf("female:pantyhose"), deleted = false)
        dao.applyDirectoryTags(1, "g/tights", listOf("タイツ"), deleted = false)
        dao.replaceTagAliases(1, aliases(1, "clothes/tights", "タイツ", "female:pantyhose"), "1")
        assertEquals(2, dao.getDirectoriesWithTag("タイツ").size)

        dao.replaceTagAliases(1, aliases(1, "clothes/tights", "タイツ", "tights"), "2")

        assertEquals(listOf("g/tights"), dao.getDirectoriesWithTag("タイツ").map { it.parentPath })
        assertEquals("2", dao.getTagAliasGeneration(1))
    }

    /** Replacing one connection's aliases leaves another's alone. */
    @Test
    fun `replacing aliases is per connection`() {
        dao.applyDirectoryTags(1, "g/a", listOf("a"), deleted = false)
        dao.applyDirectoryTags(1, "g/b", listOf("b"), deleted = false)
        dao.replaceTagAliases(2, aliases(2, "x/ab", "a", "b"), "5")
        dao.replaceTagAliases(1, emptyList(), "1")

        assertEquals(listOf("g/a", "g/b"), dao.getDirectoriesWithTag("a").map { it.parentPath })
        assertEquals("5", dao.getTagAliasGeneration(2))
    }

    /**
     * The pairs the tag search reads: each member beside every other member of its group,
     * never itself, and the same one hop as the listing. Two connections holding the same
     * file must not double them.
     */
    @Test
    fun `alias pairs are one hop, exclude the tag itself, and are distinct`() {
        val groups = aliases(1, "x/ab", "a", "b") + aliases(1, "x/bc", "b", "c")
        dao.replaceTagAliases(1, groups, "1")
        dao.replaceTagAliases(2, groups.map { it.copy(connectionId = 2) }, "1")

        assertEquals(
            listOf("a" to "b", "b" to "a", "b" to "c", "c" to "b"),
            dao.getTagAliasPairs().map { it.tag to it.alias }.sortedWith(compareBy({ it.first }, { it.second })),
        )
    }
}
