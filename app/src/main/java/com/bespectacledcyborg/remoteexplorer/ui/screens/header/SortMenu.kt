package com.bespectacledcyborg.remoteexplorer.ui.screens.header

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bespectacledcyborg.remoteexplorer.data.SortType

data class InheritedSort(
    val label: String,
    val active: Boolean,
    val onSelect: () -> Unit,
)

@Composable
fun SortMenu(
    current: SortType,
    options: List<SortType>,
    onSelect: (SortType) -> Unit,
    inherited: InheritedSort? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = !open }, content = {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort: ${current.label}")
        })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (inherited != null) {
                DropdownMenuItem(
                    text = { Text(inherited.label) },
                    onClick = {
                        inherited.onSelect()
                        open = false
                    },
                    trailingIcon = { CurrentMark(inherited.active) },
                )
                HorizontalDivider()
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                    trailingIcon = {
                        CurrentMark(option == current && inherited?.active != true)
                    },
                )
            }
        }
    }
}

@Composable
private fun CurrentMark(current: Boolean) {
    if (current) {
        Icon(Icons.Filled.Check, contentDescription = "Current")
    }
}
