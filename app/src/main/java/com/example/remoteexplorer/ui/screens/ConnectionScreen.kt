package com.example.remoteexplorer.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.remoteexplorer.data.db.Connection
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@Composable
fun ConnectionScreen(
    explorerViewModel: ExplorerViewModel
) {
    val connections by explorerViewModel.connectionsState.collectAsState()

    val connectionItems: List<Item<Connection?>> = connections.map {
        Item(
            name = it.name,
            type = "connection",
            url = "",
            item = it,
        )
    }
    val newConnection: Item<Connection?> = Item(
        name = "Add Connection",
        type = "add_connection",
        url = "",
        item = null,
    )

    val handleItemClick: (Item<Connection?>) -> () -> Unit = { item: Item<Connection?> -> {
        if (item.item == null) {
            explorerViewModel.changeScreen("add_connection")
        } else {
            explorerViewModel.connect(
                Connection(
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
        handleLongItemClick = handleLongItemClick
    )
}