package com.bespectacledcyborg.remoteexplorer.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

const val LOOPBACK_TOKEN_PARAMETER = "token"
private const val PREFERENCES_NAME = "loopback_server"
private const val TOKEN_KEY = "token"
private const val TOKEN_BYTES = 32

class LoopbackToken(
    private val preferences: SharedPreferences,
    private val random: SecureRandom = SecureRandom(),
) {
    val value: String by lazy { synchronized(STORE_LOCK) { storedOrNew() } }

    fun ensureStored() {
        value
    }

    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), value.toByteArray())

    private fun storedOrNew(): String =
        preferences.getString(TOKEN_KEY, null)
            ?: newToken().also { preferences.edit().putString(TOKEN_KEY, it).commit() }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private val STORE_LOCK = Any()

        fun of(context: Context) =
            LoopbackToken(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))
    }
}
