package com.bespectacledcyborg.remoteexplorer.data.crypto

/**
 * A string that must not be written to the database in the clear.
 *
 * It exists as a type rather than a naming convention so the compiler, not a reviewer, is what
 * stops an SMB password being persisted raw: the only way a [SecretText] reaches SQLite is
 * through the converter that encrypts it.
 *
 * Deliberately NOT a `@JvmInline value class`. Room unwraps those to their underlying column
 * type on its own, which would quietly persist the plaintext and skip the converter entirely —
 * the failure would look exactly like success. As an ordinary class, a missing converter is a
 * compile error instead.
 *
 * [toString] is redacted so the password cannot reach a log through a data class printing its
 * fields, which is how these usually escape.
 */
class SecretText(val value: String) {
    override fun toString(): String = "SecretText(redacted)"
    override fun equals(other: Any?): Boolean = other is SecretText && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/**
 * Encrypts credentials at rest. The implementation that ships holds its key in the Android
 * Keystore; tests use a fake, because `AndroidKeyStore` does not exist on the JVM and the
 * migration that re-encrypts saved rows is exactly the code that most needs a test.
 *
 * ## What this protects, and what it does not
 *
 * The threat is someone reading `remote-explorer.db` — an `adb pull` on a rooted or
 * developer-unlocked device, a backup, another app that has escaped its sandbox. Against that,
 * the ciphertext is useless without a key that never leaves the Keystore.
 *
 * It is NOT secrecy from someone holding the unlocked device: the app can decrypt on demand, so
 * anything running as this app can too. And the password still reaches SMBJ in the clear at
 * connect time, because that is what the protocol takes.
 */
interface CredentialCipher {
    /** Returns a self-describing ciphertext: [IV_PREFIX], the IV, and the payload. */
    fun encrypt(secret: SecretText): String

    /**
     * Reverses [encrypt].
     *
     * Any value NOT carrying [IV_PREFIX] is returned unchanged, which is deliberate: rows
     * written before this existed, and rows written by `tools/dev-seed-connection.sh` (which
     * talks to SQLite directly and has no way to reach the Keystore), are plaintext and must
     * keep working rather than decrypting into garbage.
     */
    fun decrypt(stored: String): SecretText

    companion object {
        /**
         * Marks a value as encrypted. A prefix rather than a separate column because it makes
         * the stored value self-describing: no row can be ambiguous about which form it is in,
         * and the schema keeps a single TEXT column.
         */
        const val IV_PREFIX = "enc:v1:"
    }
}

/** True when [stored] was produced by [CredentialCipher.encrypt] rather than left in the clear. */
fun isEncrypted(stored: String): Boolean = stored.startsWith(CredentialCipher.IV_PREFIX)
