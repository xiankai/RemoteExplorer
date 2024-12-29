package com.example.remoteexplorer.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.remoteexplorer.data.db.Connection
import com.example.remoteexplorer.ui.screens.ConnectionDetailsScreen
import com.example.remoteexplorer.ui.screens.ConnectionScreen
import com.example.remoteexplorer.ui.screens.ExplorerScreen
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import java.util.Date
import kotlin.math.exp


@Composable
fun MainView(
    modifier: Modifier,
    explorerViewModel: ExplorerViewModel,
) {
    val screen by explorerViewModel.screenState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect("") {
        explorerViewModel.toast.collect { message ->
            if(message.isNotEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler {
        explorerViewModel.goBack()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.TopStart),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (screen) {
            "connection" -> ConnectionScreen(
                explorerViewModel = explorerViewModel,
            )
            "add_connection" -> ConnectionDetailsScreen(
                connection = null,
                onSave = {
                    explorerViewModel.addConnection(it)
                 },
                onDelete = {
                    explorerViewModel.deleteConnection(it)
                },
                onCancel = { explorerViewModel.changeScreen("connection") }
            )
            "list" -> ExplorerScreen(
                explorerViewModel = explorerViewModel,
            )
        }
    }
}