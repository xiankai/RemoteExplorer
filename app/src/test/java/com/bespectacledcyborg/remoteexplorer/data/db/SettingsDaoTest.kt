package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.GridColumns
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
        repository = SettingsRepository(db.settingsDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `setting the column count leaves the rest of the row alone`() = runTest {
        db.settingsDao().insert(
            SettingsEntity(maxStorageCacheSize = 7, thumbnailCacheVolume = "card", themeMode = ThemeMode.DARK)
        )

        repository.setColumns(GridColumns(tileWidthDp = 160, legacyCount = 5))

        assertEquals(
            SettingsEntity(
                maxStorageCacheSize = 7,
                defaultColumnCount = 5,
                columnTileWidthDp = 160,
                thumbnailCacheVolume = "card",
                themeMode = ThemeMode.DARK,
            ),
            db.settingsDao().getSettingsOnce(),
        )
    }

    @Test
    fun `a collector sees a change made after it started`() = runTest {
        db.settingsDao().insert(SettingsEntity())
        val seen = mutableListOf<GridColumns>()
        val collector = launch { repository.columns().take(2).toList(seen) }
        while (seen.isEmpty()) yield()

        repository.setColumns(GridColumns(tileWidthDp = 200, legacyCount = 6))
        collector.join()

        assertEquals(listOf(GridColumns.AUTO, GridColumns(200, 6)), seen)
    }

    @Test
    fun `a collector that arrives before the default row waits for it`() = runTest {
        val seen = mutableListOf<GridColumns>()
        val collector = launch { repository.columns().take(1).toList(seen) }
        yield()

        db.settingsDao().insert(SettingsEntity(defaultColumnCount = 4))
        collector.join()

        assertEquals(listOf(GridColumns(null, 4)), seen)
    }

    @Test
    fun `setting the default sort leaves the columns alone`() = runTest {
        db.settingsDao().insert(SettingsEntity(defaultColumnCount = 3))

        repository.setDefaultSort(SortType.LAST_MODIFIED_DESC)

        val stored = db.settingsDao().getSettingsOnce()
        assertEquals(SortType.LAST_MODIFIED_DESC, stored?.defaultSort)
        assertEquals(3, stored?.defaultColumnCount)
    }
}
