package com.example.remoteexplorer.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.data.db.LocalTag
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.screens.header.TagButtons
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    explorerViewModel: ExplorerViewModel,
    onDismissDialog: () -> Unit,
) {
    val tags = explorerViewModel.tagsState.collectAsState()

    Log.i("TagsScreen", "Tags: ${tags.value}")

    val handleItemClick = { item: Item<LocalTag> -> {
        explorerViewModel.getFilesWithTag(item.item)
        onDismissDialog()
    } }

    BackHandler {
        explorerViewModel.goBack()
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
                    items = tags.value.map { Item(
                        name = it.tag,
                        type = FileType.FILE, // TODO: Add FOLDER for supporting nested tags
                        urls = emptyList(), // TODO: Add the thumbnails for tags
                        item = it,
                    ) },
                    handleItemClick = handleItemClick,
                    handleLongItemClick = {{}},
                    handleRefresh = { explorerViewModel.forceRefresh() }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}