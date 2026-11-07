package com.bespectacledcyborg.remoteexplorer.data

import android.content.Context
import android.content.SharedPreferences
import com.bespectacledcyborg.remoteexplorer.data.db.Connection

private const val PREFERENCES_NAME = "last_location"
private const val CONNECTION_ID_KEY = "connectionId"
private const val PATH_KEY = "path"

data class Location(val connection: Connection, val path: String) {
    val folderStack: List<String>
        get() = listOf("") + path.split('/')
            .filter { it.isNotEmpty() }
            .runningReduce { parent, name -> "$parent/$name" }
}

class LastLocation(private val preferences: SharedPreferences) {
    fun remember(connectionId: Int, path: String) {
        if (isPseudoFolder(path)) return
        preferences.edit()
            .putInt(CONNECTION_ID_KEY, connectionId)
            .putString(PATH_KEY, path)
            .apply()
    }

    fun forget() {
        preferences.edit().clear().apply()
    }

    fun forget(connectionId: Int) {
        if (storedConnectionId() == connectionId) forget()
    }

    fun resolve(connections: List<Connection>): Location? {
        val connectionId = storedConnectionId() ?: return null
        val connection = connections.firstOrNull { it.id == connectionId }
        if (connection == null) {
            forget()
            return null
        }
        return Location(connection, preferences.getString(PATH_KEY, null).orEmpty())
    }

    private fun storedConnectionId(): Int? =
        if (preferences.contains(CONNECTION_ID_KEY)) preferences.getInt(CONNECTION_ID_KEY, 0) else null

    companion object {
        fun of(context: Context) =
            LastLocation(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))
    }
}
