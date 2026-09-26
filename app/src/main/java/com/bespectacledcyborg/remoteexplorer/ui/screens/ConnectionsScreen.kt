package com.bespectacledcyborg.remoteexplorer.ui.screens

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.ui.components.Explorer
import com.bespectacledcyborg.remoteexplorer.ui.components.Item
import com.bespectacledcyborg.remoteexplorer.ui.components.Route
import com.bespectacledcyborg.remoteexplorer.ui.screens.header.ConnectionsButtons
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel

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

    val handleItemClick: (Item<Connection?>) -> () -> Unit = { item: Item<Connection?> ->
        {
            if (item.item == null) {
                explorerViewModel.editConnection(null)
                navHostController.navigate(Route.ConnectionDetailsRoute)
            } else {
                // The row itself. This used to rebuild it field by field, which silently
                // dropped any field added since — a local root would have arrived without
                // its type or its folder.
                explorerViewModel.connect(item.item)
                navHostController.navigate(Route.ExplorerRoute)
            }
        }
    }

    val handleLongItemClick: (Item<Connection?>) -> () -> Unit = { item: Item<Connection?> ->
        {
            if (item.item != null) {
                explorerViewModel.editConnection(item.item)
                navHostController.navigate(Route.ConnectionDetailsRoute)
            }
        }
    }

    val configuration = LocalConfiguration.current
    // Determine the screen width in dp
    val screenWidth = configuration.screenWidthDp

    // Define minimum size for the icons
    val minIconSizeDp = 128.dp // Minimum size for optimal legibility

    // Calculate the number of columns based on screen width
    val columns = (screenWidth / minIconSizeDp.value).toInt().coerceIn(2, 12)

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
                actions = {
                    ConnectionsButtons(onStorageClick = {
                        navHostController.navigate(Route.SettingsRoute)
                    })
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
                    getImageUrl = ({ url, _ -> url }),
                    items = connectionItems.plus(newConnection),
                    handleItemClick = handleItemClick,
                    handleLongItemClick = handleLongItemClick,
                    handleRefresh = {},
                    columnCount = columns
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}