package com.bespectacledcyborg.remoteexplorer.ui.screens.header

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
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
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.SortType

/** A sort button that opens a menu of [options], ticking [current]. */
@Composable
fun SortMenu(
    current: SortType,
    options: List<SortType>,
    onSelect: (SortType) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = !open }, content = {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort: ${current.label}")
        })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                    trailingIcon = {
                        if (option == current) {
                            Icon(Icons.Filled.Check, contentDescription = "Current")
                        }
                    },
                )
            }
        }
    }
}
