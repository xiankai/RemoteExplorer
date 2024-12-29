package com.example.remoteexplorer.ui

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Header(
    listViewModel: ListViewModel
) {
    val pathState by listViewModel.pathState.collectAsState()

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = { BreadcrumbHeader(
            currentPath = pathState,
            modifier = Modifier.wrapContentSize(Alignment.CenterStart),
            onBreadcrumbClick = { path -> listViewModel.changePath(path) },
        ) },
        actions = { HeaderButtons(listViewModel) },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
    )
}
