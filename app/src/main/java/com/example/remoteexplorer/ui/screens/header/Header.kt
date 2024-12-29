package com.example.remoteexplorer.ui.screens.header

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
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

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = { BreadcrumbHeader(
            currentPath = pathState,
            modifier = Modifier.wrapContentSize(Alignment.CenterStart),
            onBreadcrumbClick = { path -> explorerViewModel.changePath(path) },
        ) },
        actions = { HeaderButtons(explorerViewModel) },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
    )
}
