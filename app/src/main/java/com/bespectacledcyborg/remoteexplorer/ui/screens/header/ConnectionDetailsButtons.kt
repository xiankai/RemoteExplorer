package com.bespectacledcyborg.remoteexplorer.ui.screens.header

import android.app.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun ConnectionDetailsButtons(
    deleteConnection: () -> Unit
) {
    val context = LocalContext.current

    IconButton(
        onClick = {
            val builder = AlertDialog.Builder(context)
            builder.setMessage("Are you sure you want to Delete?")
                .setCancelable(false)
                .setPositiveButton("Yes") { dialog, id ->
                    deleteConnection()
                }
                .setNegativeButton("No") { dialog, id ->
                    // Dismiss the dialog
                    dialog.dismiss()
                }
            val alert = builder.create()
            alert.show()
        },
        content = {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete Connection",
            )
        }
    )

}