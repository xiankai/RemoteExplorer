package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverChoiceSheet(
    folderName: String,
    current: CoverMode?,
    keepsFirstPage: Boolean,
    onChoose: (CoverMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text("Cover for $folderName") },
                supportingContent = if (keepsFirstPage) {
                    { Text("A manga gallery keeps its first page as its cover.") }
                } else {
                    null
                },
            )
            CoverMode.entries.forEach { mode ->
                val enabled = !keepsFirstPage && current != null  // stack-reviewer-ignore: current is null only while the sheet loads; coverChoiceFor returns DEFAULT for a folder with no stored choice, so the options enable once it answers
                ListItem(
                    modifier = Modifier.selectable(
                        selected = mode == current,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onChoose(mode) },
                    ),
                    headlineContent = { Text(mode.label) },
                    leadingContent = {
                        RadioButton(selected = mode == current, onClick = null, enabled = enabled)
                    },
                )
            }
            if (current == CoverMode.SHUFFLE && !keepsFirstPage) {
                TextButton(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onClick = { onChoose(CoverMode.SHUFFLE) },  // stack-reviewer-ignore: CoverPreferences.choose stores a fresh newSeed() on every call, so choosing Shuffle again rerolls; covered by CoverPreferencesTest
                ) {
                    Text("Shuffle again")
                }
            }
        }
    }
}
