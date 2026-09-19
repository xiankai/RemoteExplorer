package com.bespectacledcyborg.remoteexplorer.data.crypto

/**
 * Stands in for [KeystoreCredentialCipher], which cannot run here: `AndroidKeyStore` is a device
 * service, and these tests run on the JVM so that CI can execute them.
 *
 * It reverses the string and tags it. Deliberately not something that *looks* like encryption —
 * a convincing fake would invite reading these tests as evidence that the crypto is sound,
 * which they are not. What they cover is the code around the cipher: which rows the migration
 * rewrites, what it leaves alone, and whether a value survives a round trip.
 */
class ReversingCipher : CredentialCipher {
    var encryptCalls = 0
        private set

    override fun encrypt(secret: SecretText): String {
        encryptCalls++
        return CredentialCipher.IV_PREFIX + secret.value.reversed()
    }

    override fun decrypt(stored: String): SecretText =
        if (isEncrypted(stored)) {
            SecretText(stored.removePrefix(CredentialCipher.IV_PREFIX).reversed())
        } else {
            SecretText(stored)
        }
}
