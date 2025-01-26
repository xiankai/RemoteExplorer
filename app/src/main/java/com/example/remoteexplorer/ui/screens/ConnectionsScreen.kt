package com.example.remoteexplorer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.example.remoteexplorer.data.db.Connection
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.components.Route
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    explorerViewModel: ExplorerViewModel,
    navHostController: NavHostController,
) {
    val connections by explorerViewModel.connectionsState.collectAsState()

    val connectionItems: List<Item<Connection?>> = connections.map {
        Item(
            name = it.name,
            type = FileType.CONNECTION,
            thumbnails = emptyList(),
            item = it,
        )
    }
    val newConnection: Item<Connection?> = Item(
        name = "Add Connection",
        type = FileType.ADD_CONNECTION,
        thumbnails = emptyList(),
        item = null,
    )

    val handleItemClick: (Item<Connection?>) -> () -> Unit = { item: Item<Connection?> -> {
        if (item.item == null) {
            explorerViewModel.editConnection(null)
            navHostController.navigate(Route.ConnectionDetailsRoute)
        } else {
            explorerViewModel.connect(
                Connection(
                    id = item.item.id,
                    name = item.item.name,
                    hostname = item.item.hostname,
                    username = item.item.username,
                    password = item.item.password,
                    domain = item.item.domain,
                    share = item.item.share,
                    lastModified = item.item.lastModified,
                )
            )
            navHostController.navigate(Route.ExplorerRoute)
        }
    } }

    val handleLongItemClick: (Item<Connection?>) -> () -> Unit = { item: Item<Connection?> -> {
        if (item.item != null) {
            explorerViewModel.editConnection(item.item)
            navHostController.navigate(Route.ConnectionDetailsRoute)
        }
    } }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text("Connections")
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .wrapContentSize(Alignment.TopStart),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Explorer(
                    explorerViewModel= explorerViewModel,
                    items = connectionItems.plus(newConnection),
                    handleItemClick = handleItemClick,
                    handleLongItemClick = handleLongItemClick,
                    handleRefresh = {},
                    columnCount = 12
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}