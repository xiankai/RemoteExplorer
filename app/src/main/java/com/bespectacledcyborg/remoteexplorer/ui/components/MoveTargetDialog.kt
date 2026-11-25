package com.bespectacledcyborg.remoteexplorer.ui.components

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MoveTargetDialog(
    rootPath: String,
    foldersIn: suspend (String) -> List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf(rootPath) }
    var entries by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(current) {
        loading = true
        entries = foldersIn(current)
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current.ifEmpty { "The share" }) },
        text = {
            Column {
                if (current.isNotEmpty()) {
                    Text(
                        text = "..",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { current = current.substringBeforeLast('/', "") }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (entries.isEmpty()) {
                    Text(
                        text = if (loading) "Reading…" else "No folders here",
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(entries) { path ->
                        Text(
                            text = path.substringAfterLast('/'),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { current = path }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(current) },
                enabled = !loading,
            ) { Text("Move here") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
