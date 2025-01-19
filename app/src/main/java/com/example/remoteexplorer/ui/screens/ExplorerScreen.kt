package com.example.remoteexplorer.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.data.db.LocalFile
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.components.Route
import com.example.remoteexplorer.ui.screens.header.BreadcrumbHeader
import com.example.remoteexplorer.ui.screens.header.ExplorerButtons
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import java.net.URLConnection
import java.nio.file.Paths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    explorerViewModel: ExplorerViewModel,
    navHostController: NavHostController,
) {
    val context = LocalContext.current

    val pathState by explorerViewModel.pathState.collectAsState()
    val connectionName by explorerViewModel.connectionNameState.collectAsState()
    val list = explorerViewModel.listState.collectAsState()
    val thumbnails = explorerViewModel.thumbnailsState.collectAsState()

    val handleItemClick = { item: Item<LocalFile> -> {
        val path = Paths.get(item.item.parentPath, item.item.filename).toString()
        when (item.type) {
            FileType.FOLDER -> {
                // TODO: Make new nested route for this
                explorerViewModel.changePath(path)
            }
            FileType.FILE -> {
                if (item.item.canHaveThumbnail) {
                    val browsableImages = list.value.filter { it.canHaveThumbnail }
                    val initialBrowsableImage = browsableImages.indexOf(item.item)
                    explorerViewModel.browseImages(browsableImages, initialBrowsableImage)
                    navHostController.navigate(Route.ImageBrowserRoute)
                } else {
                    val intent = Intent(Intent.ACTION_VIEW)
                    val mimeType = URLConnection.guessContentTypeFromName(item.name)
                    val url = explorerViewModel.getUrl(path)
                    intent.setDataAndType(Uri.parse(url), mimeType)

                    context.startActivity(intent)
                }
            }
            else -> {}
        }
    } }

    val handleLongItemClick = { item: Item<LocalFile> -> {
        Toast.makeText(context, item.name, Toast.LENGTH_SHORT).show()
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
                    BreadcrumbHeader(
                        // TODO: Make new nested route for this
                        rootPath = connectionName,
                        currentPath = pathState,
                        modifier = Modifier.wrapContentSize(Alignment.CenterStart),
                        onBreadcrumbClick = { path ->
                            if (path == "home") {
                                navHostController.navigate(Route.ConnectionsRoute)
                            } else {
                                // TODO: Make new nested route for this
                                explorerViewModel.changePath(path)
                            }
                        },
                    )
                },
                actions = {
                    ExplorerButtons(explorerViewModel)
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
                    items = list.value.map { Item(
                        name = it.filename,
                        type = it.type,
                        urls = thumbnails.value.getOrDefault(it.filename, emptyList()).map { explorerViewModel.getUrl(it) },
                        item = it,
                    ) },
                    handleItemClick = handleItemClick,
                    handleLongItemClick = handleLongItemClick,
                    handleRefresh = { explorerViewModel.forceRefresh() }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}