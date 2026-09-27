package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
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

    private val cipher = ReversingCipher()

    /**
     * 5 through 8 and 9 through 16 are AutoMigrations; 9 rewrites credentials and so has to
     * be supplied.
     */
    private fun migrateToLatest() =
        helper.runMigrationsAndValidate(TEST_DB, 16, true, EncryptSavedPasswords(cipher))

    /**
     * The whole declared chain, validated against the exported latest schema. This is the
     * test that would have caught `fallbackToDestructiveMigration()` wiping the cache on a
     * version bump before it reached a device, rather than after.
     */
    @Test
    fun `migrates the full chain from 5 to 16 and matches the exported schema`() {
        helper.createDatabase(TEST_DB, 5).close()

        // A future migration that nobody wires up fails here rather than on a user's device.
        migrateToLatest().close()
    }

    /**
     * A cached listing from before 10 has no sync record, and must survive the upgrade
     * intact: those rows are what the explorer paints from on the first launch afterwards,
     * before the first reconcile has run. Losing them would turn the upgrade into a blank
     * screen for as long as the share takes to answer.
     */
    @Test
    fun `keeps the cached listing across the table added in 10`() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO LocalFile
                    (connectionId, parentPath, filename, type, canHaveThumbnail, tagStatus, thumbnailStatus, lastModified)
                VALUES (1, 'Photos', 'beach.jpg', 'FILE', 1, 'SUCCESS', 'SUCCESS', 0)
                """.trimIndent()
            )
        }

        migrateToLatest().use { db ->
            db.query("SELECT filename, tagStatus, thumbnailStatus FROM LocalFile").use { row ->
                assertTrue("the cached listing did not survive the migration", row.moveToFirst())
                assertEquals("beach.jpg", row.getString(0))
                // The derived state is the expensive part — a thumbnail already generated and
                // a sidecar already parsed. An upgrade must not throw either away.
                assertEquals("SUCCESS", row.getString(1))
                assertEquals("SUCCESS", row.getString(2))
            }

            db.query("SELECT COUNT(*) FROM DirectorySync").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("pre-10 rows must read as never reconciled", 0, row.getInt(0))
            }
        }
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

        migrateToLatest().use { db ->
            db.query("SELECT name, hostname, username, password, share, port FROM Connection").use { row ->
                assertTrue("the saved connection did not survive the migration", row.moveToFirst())
                assertEquals("fixture", row.getString(0))
                assertEquals("10.0.2.2", row.getString(1))
                assertEquals("smbuser", row.getString(2))
                // Encrypted by 9 on the way through, so the check is a round trip rather than
                // a literal — the credential has to come back out intact, not stay readable.
                assertEquals(SecretText("smbpass"), cipher.decrypt(row.getString(3)))
                assertEquals("media", row.getString(4))
                assertEquals(DEFAULT_SMB_PORT, row.getInt(5))
            }
        }
    }

    /**
     * The settings row must come through 11 with its existing values and a NULL cache
     * volume — which is the value that means internal storage, so the upgrade leaves the
     * thumbnail cache exactly where it already was. A non-null default here would silently
     * move every user's cache onto a card on first launch, and take the existing one with
     * it.
     */
    @Test
    fun `keeps settings across 11 and leaves the cache on internal storage`() {
        // From 7 rather than 5, which is where the settings table first exists.
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO settings (id, maxStorageCacheSize, defaultColumnCount) VALUES (0, 7, 5)"
            )
        }

        migrateToLatest().use { db ->
            db.query("SELECT maxStorageCacheSize, defaultColumnCount, thumbnailCacheVolume FROM settings")
                .use { row ->
                    assertTrue("the settings row did not survive the migration", row.moveToFirst())
                    assertEquals(7, row.getInt(0))
                    assertEquals(5, row.getInt(1))
                    assertTrue("the cache must stay on internal storage", row.isNull(2))
                }
        }
    }

    /**
     * An existing install comes through 12 with no ingest state, which reads as "never
     * pulled" and starts from a full pull. A migration that invented one would make the
     * first pass resume from an offset into a log it has never read.
     */
    @Test
    fun `arrives at 12 with no tag index ingested`() {
        helper.createDatabase(TEST_DB, 5).close()

        migrateToLatest().use { db ->
            db.query("SELECT COUNT(*) FROM TagIndexIngest").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("an upgrade must not claim to have read a log", 0, row.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM DirTagRelation").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        }
    }

    /** Likewise 13: no alias generation, so the first pass takes the whole alias file. */
    @Test
    fun `arrives at 13 with no aliases ingested`() {
        helper.createDatabase(TEST_DB, 5).close()

        migrateToLatest().use { db ->
            db.query("SELECT COUNT(*) FROM TagAliasIngest").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM TagAlias").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        }
    }

    /**
     * 14 rebuilds the Connection table, because SQLite cannot make a column nullable in
     * place. A rebuild is a copy, and a copy is where rows go missing — so every existing
     * connection has to come out as the share it was, its stored ciphertext untouched, and
     * still unique by name.
     */
    @Test
    fun `keeps a saved share through the Connection rebuild in 14`() {
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(
                """
                INSERT INTO Connection (id, name, hostname, port, username, password, domain, share, lastModified)
                VALUES (3, 'nas', 'nas.local', 1445, 'explorer', 'enc:v1:opaque', 'WORKGROUP', 'elements', 42)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 14, true).use { db ->
            db.query(
                "SELECT id, name, type, hostname, port, username, password, domain, share, localRoot, lastModified FROM Connection"
            ).use { row ->
                assertTrue("the saved share did not survive the rebuild", row.moveToFirst())
                assertEquals(3, row.getInt(0))
                assertEquals("nas", row.getString(1))
                assertEquals("an existing row is a share", "SMB", row.getString(2))
                assertEquals("nas.local", row.getString(3))
                assertEquals(1445, row.getInt(4))
                assertEquals("explorer", row.getString(5))
                // Copied, not re-encrypted: the rebuild must not pass through the converter.
                assertEquals("enc:v1:opaque", row.getString(6))
                assertEquals("WORKGROUP", row.getString(7))
                assertEquals("elements", row.getString(8))
                assertTrue(row.isNull(9))
                assertEquals(42L, row.getLong(10))
            }

            // The unique index on name came back with the table.
            val duplicate = runCatching {
                db.execSQL(
                    "INSERT INTO Connection (name, lastModified) VALUES ('nas', 0)"
                )
            }
            assertTrue("a second 'nas' must still be refused", duplicate.isFailure)

            // And a local root, with none of the SMB fields, is now a row the table accepts.
            db.execSQL(
                "INSERT INTO Connection (name, type, localRoot, lastModified) " +
                        "VALUES ('phone', 'LOCAL', '/storage/emulated/0/Sync', 0)"
            )
        }
    }

    /** 15: an existing install comes out with its connections and no pairings. */
    @Test
    fun `arrives at 15 with connections intact and nothing paired`() {
        helper.createDatabase(TEST_DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO Connection (id, name, type, localRoot, lastModified) " +
                        "VALUES (3, 'phone', 'LOCAL', '/storage/emulated/0/Sync', 0)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 15, true).use { db ->
            db.query("SELECT name, type, localRoot FROM Connection").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("phone", row.getString(0))
                assertEquals("LOCAL", row.getString(1))
                assertEquals("/storage/emulated/0/Sync", row.getString(2))
            }
            db.query("SELECT COUNT(*) FROM Pairing").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
