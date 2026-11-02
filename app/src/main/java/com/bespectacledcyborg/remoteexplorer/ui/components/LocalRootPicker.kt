package com.bespectacledcyborg.remoteexplorer.ui.components

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.isSyncthingInternal
import com.bespectacledcyborg.remoteexplorer.utils.NaturalOrder
import java.io.File

/** A mounted storage volume, by the name the system gives it and where it is mounted. */
data class StorageRoot(val label: String, val directory: File)

/**
 * Every mounted volume, internal shared storage first and then any card.
 *
 * Read from [StorageManager] rather than guessed at: a card mounts under `/storage/<uuid>`,
 * and the uuid is only known to the system.
 */
fun mountedStorageRoots(context: Context): List<StorageRoot> {
    val storageManager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
    return storageManager.storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED }
        .sortedBy { it.isRemovable }
        .mapNotNull { volume ->
            volume.directory?.let { StorageRoot(volume.getDescription(context), it) }
        }
}

@Composable
fun LocalRootPickerDialog(
    initial: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val roots = remember { mountedStorageRoots(context) }
    var current by remember {
        mutableStateOf(initial?.let(::File)?.takeIf { it.isDirectory })
    }
    val atVolumeRoot = current != null && roots.any { it.directory == current }

    val entries: List<Pair<String, File>> = current?.let { dir ->
        dir.listFiles { file -> file.isDirectory && !isSyncthingInternal(file.name) }
            .orEmpty()
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { it.name to it }
    } ?: roots.map { it.label to it.directory }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current?.path ?: "Choose storage") },
        text = {
            Column {
                if (current != null) {
                    Text(
                        text = "..",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                current = if (atVolumeRoot) null else current?.parentFile
                            }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (entries.isEmpty()) {
                    Text(
                        text = if (current == null) "No storage is mounted" else "No folders here",
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(entries) { (label, dir) ->
                        Text(
                            text = label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { current = dir }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { current?.let { onPick(it.path) } },
                enabled = current != null,
            ) { Text("Use this folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
