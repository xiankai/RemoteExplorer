package com.example.remoteexplorer.ui.screens.header

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Header(
    explorerViewModel: ExplorerViewModel
) {
    val pathState by explorerViewModel.pathState.collectAsState()
    val screenState by explorerViewModel.screenState.collectAsState()
    val connectionName by explorerViewModel.connectionNameState.collectAsState()

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            if (screenState == "list") {
                BreadcrumbHeader(
                    rootPath = connectionName,
                    currentPath = pathState,
                    modifier = Modifier.wrapContentSize(Alignment.CenterStart),
                    onBreadcrumbClick = { path ->
                        if (path == "home") {
                            explorerViewModel.changeScreen("connection")
                        } else {
                            explorerViewModel.changePath(path)
                        }
                    },
                )
            } else if (screenState == "image_browser") {
                Text("Image Browser")
            } else {
                Text("Connections")
            }
        },
        actions = {
            if (screenState == "list") {
                ExplorerButtons(explorerViewModel)
            } else if (screenState == "image_browser") {
                ImageBrowserButtons(explorerViewModel)
            } else {
                ConnectionButtons(explorerViewModel)
            } },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
    )
}
