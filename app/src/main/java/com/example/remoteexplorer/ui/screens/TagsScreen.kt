package com.example.remoteexplorer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.data.db.LocalTag
import com.example.remoteexplorer.data.db.LocalTagWithMetadata
import com.example.remoteexplorer.data.tagPrefix
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.components.Route
import com.example.remoteexplorer.ui.screens.header.TagButtons
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    explorerViewModel: ExplorerViewModel,
    navHostController: NavHostController,
) {
    val tags = explorerViewModel.tagsState.collectAsState()
    val tagThumbnails by explorerViewModel.tagThumbnailsState.collectAsState()
    val viewType by explorerViewModel.tagScreenViewTypeState.collectAsState()

    val handleItemClick = { item: Item<LocalTagWithMetadata> ->
        {
            explorerViewModel.getFilesWithTag(LocalTag(item.item.tag))
            navHostController.navigate(Route.ExplorerRoute)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text("Tags")
                },
                navigationIcon = {
                    IconButton(onClick = { navHostController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Localized description"
                        )
                    }
                },
                actions = {
                    TagButtons(explorerViewModel)
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
                    getImageUrl = { explorerViewModel.getUrl(it) },
                    items = tags.value.map {
                        Item(
                            name = "${it.tag} (${it.count})",
                            type = FileType.FOLDER, // TODO: Support nested tags
                            thumbnails = tagThumbnails.getOrDefault(
                                "${tagPrefix}${it.tag}",
                                emptyList()
                            ),
                            item = it,
                        )
                    },
                    handleItemClick = handleItemClick,
                    handleLongItemClick = { {} },
                    handleRefresh = { explorerViewModel.forceRefresh() },
                    columnCount = 4,
                    viewType = viewType
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}