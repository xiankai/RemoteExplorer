package com.example.remoteexplorer.ui.screens.header

import android.app.AlertDialog
import android.security.ConfirmationPrompt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@Composable
fun ConnectionButtons(
   explorerViewModel: ExplorerViewModel
) {
    val connection = explorerViewModel.connectionState.collectAsState().value

    val context = LocalContext.current

    IconButton(
        onClick = { if (connection != null) {
            val builder = AlertDialog.Builder(context)
            builder.setMessage("Are you sure you want to Delete?")
                .setCancelable(false)
                .setPositiveButton("Yes") { dialog, id ->
                    explorerViewModel.deleteConnection(connection)
                }
                .setNegativeButton("No") { dialog, id ->
                    // Dismiss the dialog
                    dialog.dismiss()
                }
            val alert = builder.create()
            alert.show()
        } },
        content = {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete Connection",
            )
        }
    )

}