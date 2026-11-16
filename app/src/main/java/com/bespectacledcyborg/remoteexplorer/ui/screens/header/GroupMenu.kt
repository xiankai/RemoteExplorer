package com.bespectacledcyborg.remoteexplorer.ui.screens.header

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bespectacledcyborg.remoteexplorer.data.GroupMode

@Composable
fun GroupMenu(current: GroupMode, onSelect: (GroupMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = !open }, content = {
            Icon(Icons.Filled.ViewAgenda, contentDescription = "Group: ${current.label}")
        })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GroupMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        onSelect(mode)
                        open = false
                    },
                    trailingIcon = {
                        if (mode == current) Icon(Icons.Filled.Check, contentDescription = "Current")
                    },
                )
            }
        }
    }
}
