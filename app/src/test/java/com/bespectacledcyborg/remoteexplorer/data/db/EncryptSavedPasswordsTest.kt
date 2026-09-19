package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bespectacledcyborg.remoteexplorer.data.crypto.CredentialCipher
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import com.bespectacledcyborg.remoteexplorer.data.crypto.isEncrypted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the 8 → 9 migration, which turns the passwords already sitting in plaintext on a
 * device into ciphertext. This is the step with no second chance: it rewrites the only copy of
 * a credential the user typed, so getting it wrong means they cannot reach their own share.
 *
 * The real cipher is Keystore-backed and does not exist on the JVM, so this uses a reversible
 * fake. What is being tested is the migration's behaviour — which rows it touches, what it
 * leaves behind, whether it can run twice — not AES.
 */
@RunWith(RobolectricTestRunner::class)
class EncryptSavedPasswordsTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private val cipher = ReversingCipher()

    private fun seedV8(vararg passwords: String) {
        helper.createDatabase(TEST_DB, 8).use { db ->
            passwords.forEachIndexed { index, password ->
                db.execSQL(
                    """
                    INSERT INTO Connection (id, name, hostname, port, username, password, domain, share, lastModified)
                    VALUES (${index + 1}, 'share$index', '10.0.2.2', 445, 'smbuser', '$password', '', 'media', 0)
                    """.trimIndent()
                )
            }
        }
    }

    private fun passwordsAtV9(): List<String> =
        helper.runMigrationsAndValidate(TEST_DB, 9, true, EncryptSavedPasswords(cipher)).use { db ->
            db.query("SELECT password FROM Connection ORDER BY id").use { row ->
                buildList {
                    while (row.moveToNext()) add(row.getString(0))
                }
            }
        }

    @Test
    fun `rewrites every saved password so no plaintext is left in the file`() {
        seedV8("hunter2", "correct horse")

        val stored = passwordsAtV9()

        assertEquals(2, stored.size)
        stored.forEach { assertTrue("row was left in the clear: $it", isEncrypted(it)) }
        assertFalse("the plaintext survived the migration", stored.any { it.contains("hunter2") })
        assertEquals("hunter2", cipher.decrypt(stored[0]).value)
        assertEquals("correct horse", cipher.decrypt(stored[1]).value)
    }

    /**
     * A migration killed partway — the process dies between the UPDATE and the version bump —
     * runs again on next launch. Encrypting an already-encrypted value would leave a password
     * nothing can recover, so the pass has to skip rows it has already done.
     */
    @Test
    fun `leaves already encrypted rows alone so a re-run cannot double encrypt`() {
        seedV8("hunter2")
        val afterFirstRun = passwordsAtV9()
        val callsAfterFirst = cipher.encryptCalls

        // Re-open at 9 — no migration runs, the file is already there — and drive the body
        // again by hand, which is what a process killed mid-upgrade gets on the next launch.
        val afterSecondRun = helper.runMigrationsAndValidate(TEST_DB, 9, true).use { db ->
            EncryptSavedPasswords(cipher).migrate(db)
            db.query("SELECT password FROM Connection ORDER BY id").use { row ->
                buildList {
                    while (row.moveToNext()) add(row.getString(0))
                }
            }
        }

        assertEquals("a second pass re-encrypted a row", callsAfterFirst, cipher.encryptCalls)
        assertEquals("the stored value changed on a second pass", afterFirstRun, afterSecondRun)
        assertEquals("hunter2", cipher.decrypt(afterSecondRun[0]).value)
    }

    /**
     * The plaintext tolerance that keeps `tools/dev-seed-connection.sh` working — it writes
     * straight into SQLite and has no way to reach the Keystore, so seeded rows stay in the
     * clear and must not be decrypted into garbage. This asserts the shared predicate both the
     * cipher and the migration branch on, rather than anything about the fake.
     */
    @Test
    fun `tells an encrypted value from one written outside the app`() {
        assertFalse(isEncrypted("seeded-by-the-dev-script"))
        assertTrue(isEncrypted(CredentialCipher.IV_PREFIX + "whatever"))
    }

    private companion object {
        const val TEST_DB = "encrypt-migration-test"
    }
}
