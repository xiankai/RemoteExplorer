package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.Pairings
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing

/** How a folder under a connection reads: its name then the folder, or the name alone. */
private fun describe(connection: Connection?, subPath: String): String {
    val name = connection?.name ?: "(deleted connection)"
    return if (subPath.isEmpty()) name else "$name » $subPath"
}

/**
 * The pairings that involve [connection], for its edit screen.
 *
 * A local root's are editable: which share each of its folders is a copy of. A share's are
 * shown only — where on this device it is copied to — since a pairing belongs to the copy,
 * and editing it from the share's side would be a second place to do the same thing.
 */
@Composable
fun PairingSection(
    connection: Connection,
    connections: List<Connection>,
    pairings: List<Pairing>,
    onSave: (Pairing) -> Unit,
    onDelete: (Pairing) -> Unit,
    onOpenShare: (Connection) -> Unit = {},
    onEditShare: (Connection) -> Unit = {},
) {
    val byId = connections.associateBy { it.id }
    var adding by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        when (connection.type) {
            ConnectionType.LOCAL -> {
                val mine = pairings.filter { it.localConnectionId == connection.id }
                Text("Copy of a share", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Files here share thumbnails and tags with the same files on the " +
                            "share, and are read from this device when they are here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (mine.isEmpty()) Text("Not paired with a share")
                mine.forEach { pairing ->
                    val share = byId[pairing.remoteConnectionId]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${describe(connection, pairing.localSubPath)}  ←  " +
                                    describe(share, pairing.remoteSubPath),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onDelete(pairing) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove pairing")
                        }
                    }
                    if (share != null) {
                        Row {
                            TextButton(onClick = { onOpenShare(share) }) { Text("Open ${share.name}") }
                            TextButton(onClick = { onEditShare(share) }) { Text("Edit ${share.name}") }
                        }
                    }
                }
                TextButton(onClick = { adding = true }) { Text("Pair with a share") }
            }

            ConnectionType.SMB -> {
                // Nothing at all for a share nobody has copied, rather than a heading over
                // an empty list — most shares are never copied.
                val copies = pairings.filter { it.remoteConnectionId == connection.id }
                if (copies.isNotEmpty()) {
                    Text("Copied to this device", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    copies.forEach { pairing ->
                        Text(
                            text = "${describe(connection, pairing.remoteSubPath)}  →  " +
                                    describe(byId[pairing.localConnectionId], pairing.localSubPath),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        PairingDialog(
            localConnectionId = connection.id,
            shares = connections.filter { it.type == ConnectionType.SMB },
            onSave = {
                onSave(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingDialog(
    localConnectionId: Int,
    shares: List<Connection>,
    onSave: (Pairing) -> Unit,
    onDismiss: () -> Unit,
) {
    var share by remember { mutableStateOf(shares.singleOrNull()) }
    var choosing by remember { mutableStateOf(false) }
    var localSubPath by remember { mutableStateOf("") }
    var remoteSubPath by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with a share") },
        text = {
            Column {
                if (shares.isEmpty()) {
                    Text("Add the share as a connection first.")
                    return@Column
                }
                ExposedDropdownMenuBox(expanded = choosing, onExpandedChange = { choosing = it }) {
                    OutlinedTextField(
                        value = share?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Share") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(choosing) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        shares.forEach {
                            DropdownMenuItem(
                                text = { Text(it.name) },
                                onClick = {
                                    share = it
                                    choosing = false
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = remoteSubPath,
                    onValueChange = { remoteSubPath = it },
                    label = { Text("Folder on the share") },
                    placeholder = { Text("Blank for the whole share") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = localSubPath,
                    onValueChange = { localSubPath = it },
                    label = { Text("Folder here") },
                    placeholder = { Text("Blank for this whole folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = share != null,
                onClick = {
                    share?.let {
                        onSave(
                            Pairing(
                                localConnectionId = localConnectionId,
                                // Stored the way every path in the app is spelled: no
                                // leading or trailing slash, empty for the root.
                                localSubPath = Pairings.normalize(localSubPath.trim()),
                                remoteConnectionId = it.id,
                                remoteSubPath = Pairings.normalize(remoteSubPath.trim()),
                            )
                        )
                    }
                },
            ) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
