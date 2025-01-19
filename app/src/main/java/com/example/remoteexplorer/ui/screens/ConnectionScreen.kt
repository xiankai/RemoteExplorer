package com.example.remoteexplorer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.remoteexplorer.data.db.Connection
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import kotlin.math.exp

@Composable
fun ConnectionScreen(
    explorerViewModel: ExplorerViewModel
) {
    val connections by explorerViewModel.connectionsState.collectAsState()

    val connectionItems: List<Item<Connection?>> = connections.map {
        Item(
            name = it.name,
            type = FileType.CONNECTION,
            urls = emptyList(),
            item = it,
        )
    }
    val newConnection: Item<Connection?> = Item(
        name = "Add Connection",
        type = FileType.ADD_CONNECTION,
        urls = emptyList(),
        item = null,
    )

    val handleItemClick: (Item<Connection?>) -> () -> Unit = { item: Item<Connection?> -> {
        if (item.item == null) {
            explorerViewModel.changeScreen("add_connection")
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
        }
    } }

    val handleLongItemClick: (Item<Connection?>) -> () -> Unit = { item: Item<Connection?> -> {
        if (item.item != null) {
            explorerViewModel.editConnection(item.item)
        }
    } }

    Explorer(
        items = connectionItems.plus(newConnection),
        handleItemClick = handleItemClick,
        handleLongItemClick = handleLongItemClick,
        handleRefresh = {},
    )
}