package com.bespectacledcyborg.remoteexplorer.ui.screens.header

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

@Composable
fun ConnectionsButtons(
    onStorageClick: () -> Unit
) {
    IconButton(
        onClick = onStorageClick,
        content = {
            Icon(
                Icons.Filled.Storage,
                contentDescription = "Settings",
            )
        }
    )

}