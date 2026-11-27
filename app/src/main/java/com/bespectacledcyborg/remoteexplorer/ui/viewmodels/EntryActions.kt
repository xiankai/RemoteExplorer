package com.bespectacledcyborg.remoteexplorer.ui.viewmodels

import com.bespectacledcyborg.remoteexplorer.data.EntrySize
import com.bespectacledcyborg.remoteexplorer.data.Refusal

enum class EntryAction { NEW_FOLDER, RENAME, MOVE }

data class EntryRequest(
    val action: EntryAction,
    val connectionId: Int,
    val path: String,
    val name: String = "",
    val toFolder: String = "",
    val isFolder: Boolean = false,
    val confirmedOnNas: Boolean = false,
    val confirmedLarge: Boolean = false,
)

sealed interface EntryDialog {
    data class NewFolder(val parent: String) : EntryDialog
    data class Rename(val request: EntryRequest) : EntryDialog
    data class Move(val request: EntryRequest) : EntryDialog
    data class ConfirmNas(val request: EntryRequest) : EntryDialog
    data class ConfirmLarge(val request: EntryRequest, val size: EntrySize) : EntryDialog
}

fun Refusal.reason(): String = when (this) {
    Refusal.INVALID_NAME -> "That name can't be used here."
    Refusal.NOT_FOUND -> "That entry isn't there any more."
    Refusal.LOCAL_UNAVAILABLE -> "The tablet's copy can't be opened right now."
    Refusal.NAS_UNAVAILABLE -> "The share can't be reached right now."
    Refusal.NAS_WRITE_REFUSED -> "The share refused the change. It may be read-only, or a file inside is open."
    Refusal.NEEDS_NAS_CONFIRMATION -> "That entry is only on the share."
    Refusal.CROSSES_SIDES -> "That would move the entry between the tablet and the share."
    Refusal.SYNC_PENDING -> "Syncthing hasn't caught up with an earlier move yet."
    Refusal.NAME_TAKEN -> "Something with that name is already there."
    Refusal.INTO_ITSELF -> "A folder can't be moved into itself."
    Refusal.CROSSES_PAIRING -> "That would break a pairing between the tablet and the share."
    Refusal.HOLDS_PAIRED_FOLDER -> "That folder holds a paired folder."
    Refusal.SYNCTHING_FOLDER -> "That folder is, or holds, a Syncthing folder."
    Refusal.OUTSIDE_SYNCTHING_FOLDERS -> "That's outside every Syncthing folder, so the change would stay on the tablet."
    Refusal.CROSSES_SYNCTHING_FOLDERS -> "That would move the entry between Syncthing folders."
}
