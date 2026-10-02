package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
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

/**
 * The column count is one stored value that the grid follows (#71): it used to be read once
 * at startup, so a change made anywhere did nothing until a restart.
 */
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

        repository.setColumnCount(5)

        assertEquals(
            SettingsEntity(
                maxStorageCacheSize = 7,
                defaultColumnCount = 5,
                thumbnailCacheVolume = "card",
                themeMode = ThemeMode.DARK,
            ),
            db.settingsDao().getSettingsOnce(),
        )
    }

    @Test
    fun `a collector sees a change made after it started`() = runTest {
        db.settingsDao().insert(SettingsEntity())
        val counts = mutableListOf<Int>()
        val collector = launch { repository.columnCount().take(2).toList(counts) }
        // The first emission, before the write, so the write is the second one.
        while (counts.isEmpty()) yield()

        repository.setColumnCount(6)
        collector.join()

        assertEquals(listOf(3, 6), counts)
    }

    @Test
    fun `a collector that arrives before the default row waits for it`() = runTest {
        val counts = mutableListOf<Int>()
        val collector = launch { repository.columnCount().take(1).toList(counts) }
        yield()

        db.settingsDao().insert(SettingsEntity(defaultColumnCount = 4))
        collector.join()

        assertEquals(listOf(4), counts)
    }
}
