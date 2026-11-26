package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryActionSheet(
    entryName: String,
    isFolder: Boolean,
    onOpenInViewer: (() -> Unit)?,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCover: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            ListItem(headlineContent = { Text(entryName) })
            if (onOpenInViewer != null) {
                ListItem(
                    headlineContent = { Text("Open in viewer") },
                    modifier = Modifier.clickable(onClick = onOpenInViewer),
                )
            }
            ListItem(
                headlineContent = { Text("Rename…") },
                modifier = Modifier.clickable(onClick = onRename),
            )
            ListItem(
                headlineContent = { Text("Move to…") },
                modifier = Modifier.clickable(onClick = onMove),
            )
            if (isFolder) {
                ListItem(
                    headlineContent = { Text("Cover…") },
                    modifier = Modifier.clickable(onClick = onCover),
                )
            }
        }
    }
}
