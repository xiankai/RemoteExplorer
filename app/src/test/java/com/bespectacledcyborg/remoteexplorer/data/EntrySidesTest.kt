package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val NAS = 1
private const val TABLET = 3

class EntrySidesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: java.io.File
    private val local = LocalFileDataSource(hasAllFilesAccess = { true })
    private var pairings = Pairings(listOf(Pairing(TABLET, "", NAS, "Library")))
    private val shareNames = mutableMapOf<String, Set<String>>()

    private val sides by lazy {
        EntrySides(
            local = local,
            isLocalRoot = { it == TABLET },
            pairings = { pairings },
            shareNamesLastListed = { _, path -> shareNames[path] },
            dispatcher = Dispatchers.Unconfined,
        )
    }

    @Before
    fun setUp() {
        root = folder.newFolder("SDSyncthing")
        local.connect(LocalRootDetails(TABLET, root.path))
        listOf("Download/.stfolder", "Download/pixiv", "wew/.stfolder", "loose", "stale").forEach {
            java.io.File(root, it).mkdirs()
        }
    }

    @Test
    fun `at the unsynced top level, NAS-only and tablet-only entries are told apart`() = runTest {
        shareNames[""] = setOf("download", "wew", "stale", "anime")

        assertEquals(
            mapOf("Anime" to EntrySide.NAS_ONLY, "loose" to EntrySide.TABLET_ONLY),
            sides.of(TABLET, "", listOf("Download", "wew", "loose", "stale", "Anime")),
        )
    }

    @Test
    fun `a Syncthing folder missing from the share listing is not called tablet-only`() = runTest {
        shareNames[""] = emptySet()

        assertEquals(
            mapOf("loose" to EntrySide.TABLET_ONLY, "stale" to EntrySide.TABLET_ONLY),
            sides.of(TABLET, "", listOf("Download", "wew", "loose", "stale")),
        )
    }

    @Test
    fun `before the share has been listed, nothing on the tablet gets a badge`() = runTest {
        assertEquals(
            mapOf("Anime" to EntrySide.NAS_ONLY),
            sides.of(TABLET, "", listOf("Download", "loose", "Anime")),
        )
    }

    @Test
    fun `inside a Syncthing folder only entries missing from the tablet are marked`() = runTest {
        shareNames["Download"] = emptySet()

        assertEquals(
            mapOf("fresh" to EntrySide.NAS_ONLY),
            sides.of(TABLET, "Download", listOf("pixiv", "fresh")),
        )
    }

    @Test
    fun `everything inside a folder the tablet lacks is NAS-only`() = runTest {
        assertEquals(
            mapOf("show" to EntrySide.NAS_ONLY, "film" to EntrySide.NAS_ONLY),
            sides.of(TABLET, "Anime", listOf("show", "film")),
        )
    }

    @Test
    fun `an unpaired local root and a share get no badges`() = runTest {
        assertEquals(emptyMap<String, EntrySide>(), sides.of(NAS, "Library", listOf("Anime")))

        pairings = Pairings.NONE
        assertEquals(emptyMap<String, EntrySide>(), sides.of(TABLET, "", listOf("loose", "Anime")))
    }
}
