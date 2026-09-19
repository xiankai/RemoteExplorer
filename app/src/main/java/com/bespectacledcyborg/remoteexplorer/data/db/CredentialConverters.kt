package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import com.bespectacledcyborg.remoteexplorer.data.crypto.CredentialCipher
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText

/**
 * The single boundary where credentials change form. Every read of a [SecretText] column comes
 * out decrypted and every write goes in encrypted, so no call site can forget — which is why
 * this is a converter rather than something the repository remembers to call.
 *
 * `@ProvidedTypeConverter` because the cipher is constructed at runtime (the Keystore one on a
 * device, a fake under test) and Room otherwise insists on a no-argument constructor.
 */
@ProvidedTypeConverter
class CredentialConverters(private val cipher: CredentialCipher) {

    @TypeConverter
    fun encrypt(secret: SecretText): String = cipher.encrypt(secret)

    @TypeConverter
    fun decrypt(stored: String): SecretText = cipher.decrypt(stored)
}
