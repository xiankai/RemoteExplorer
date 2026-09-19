package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the exported migration chain against a real SQLite, because this database is a
 * cache of the remote listing *and* the only record of saved connections — a migration that
 * drops a row loses the user's credentials, not a re-fetchable thumbnail.
 *
 * Runs under Robolectric rather than on a device, deliberately: `MigrationTestHelper` needs an
 * `Instrumentation` even for pure SQL, and Robolectric supplies one on the JVM. That keeps this
 * inside `testDebugUnitTest`, which is the half of the build CI can run — an instrumented test
 * would need the arm64 emulator and the NDK, and CI has neither.
 *
 * The schemas are found through the asset path added in `build.gradle.kts`, under a directory
 * named after the database CLASS — so a package rename moves it. See AGENTS.md.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * The whole declared chain, validated against the exported v8 schema. This is the test that
     * would have caught `fallbackToDestructiveMigration()` wiping the cache on a version bump
     * before it reached a device, rather than after.
     */
    @Test
    fun `migrates the full chain from 5 to 8 and matches the exported schema`() {
        helper.createDatabase(TEST_DB, 5).close()

        // Empty migration list: everything from 5 to 8 is an AutoMigration today, and a future
        // hand-written one then has to be added here deliberately rather than slipping through.
        helper.runMigrationsAndValidate(TEST_DB, 8, true).close()
    }

    /**
     * A saved connection must survive the upgrade and come out carrying the port column v8
     * added. `Connection.port` exists because macOS occupies 445, so a default that failed to
     * apply would break every existing connection on exactly the machine that needs it.
     */
    @Test
    fun `keeps saved connections across the chain and defaults the port added in 8`() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO Connection (id, name, hostname, username, password, domain, share, lastModified)
                VALUES (1, 'fixture', '10.0.2.2', 'smbuser', 'smbpass', '', 'media', 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 8, true).use { db ->
            db.query("SELECT name, hostname, username, password, share, port FROM Connection").use { row ->
                assertTrue("the saved connection did not survive the migration", row.moveToFirst())
                assertEquals("fixture", row.getString(0))
                assertEquals("10.0.2.2", row.getString(1))
                assertEquals("smbuser", row.getString(2))
                assertEquals("smbpass", row.getString(3))
                assertEquals("media", row.getString(4))
                assertEquals(DEFAULT_SMB_PORT, row.getInt(5))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
