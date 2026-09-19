package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bespectacledcyborg.remoteexplorer.data.crypto.CredentialCipher
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import com.bespectacledcyborg.remoteexplorer.data.crypto.isEncrypted

/**
 * Re-encrypts the passwords already saved on a device. Hand-written rather than an
 * `AutoMigration` because the schema does not change at all — the column stays TEXT, and it is
 * the *contents* that move from plaintext to ciphertext. Room has no way to express that.
 *
 * The version bump to 9 exists purely to give this a hook to run in.
 *
 * Idempotent by construction: a row that already carries the encrypted prefix is left alone, so
 * a partially applied migration (killed mid-upgrade) re-runs without double-encrypting and
 * locking the user out of their own share.
 */
class EncryptSavedPasswords(private val cipher: CredentialCipher) : Migration(8, 9) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT id, password FROM Connection").use { row ->
            val updates = mutableListOf<Pair<Int, String>>()
            while (row.moveToNext()) {
                val id = row.getInt(0)
                val stored = row.getString(1) ?: ""
                if (!isEncrypted(stored)) {
                    updates += id to cipher.encrypt(SecretText(stored))
                }
            }
            // Collected first, applied after: writing to a table while its cursor is open is
            // the kind of thing SQLite tolerates until it doesn't.
            updates.forEach { (id, encrypted) ->
                db.execSQL("UPDATE Connection SET password = ? WHERE id = ?", arrayOf(encrypted, id))
            }
        }
    }
}
