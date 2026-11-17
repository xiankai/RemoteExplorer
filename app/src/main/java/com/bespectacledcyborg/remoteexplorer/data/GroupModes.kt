package com.bespectacledcyborg.remoteexplorer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val PREFERENCES_NAME = "group_modes"

enum class GroupMode(val label: String) {
    UNGROUPED("Ungrouped"),
    BY_DATE("By date"),
    BY_PARENT_FOLDER("By parent folder"),
}

class GroupModes(private val preferences: SharedPreferences) {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun modeAt(folder: FileIdentity): GroupMode {
        val stored = preferences.getString(keyOf(folder), null)
        return GroupMode.entries.firstOrNull { it.name == stored } ?: GroupMode.UNGROUPED
    }

    fun choose(folder: FileIdentity, mode: GroupMode) {
        val editor = preferences.edit()
        if (mode == GroupMode.UNGROUPED) editor.remove(keyOf(folder)) else editor.putString(keyOf(folder), mode.name)
        editor.apply()
        _changes.update { it + 1 }
    }

    private fun keyOf(folder: FileIdentity) = "${folder.connectionId}:${folder.path}"

    companion object {
        fun of(context: Context) =
            GroupModes(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))
    }
}
