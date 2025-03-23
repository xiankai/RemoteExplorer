package com.bespectacledcyborg.remoteexplorer.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.ui.components.Explorer
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.ui.components.Item
import com.bespectacledcyborg.remoteexplorer.ui.components.Route
import com.bespectacledcyborg.remoteexplorer.ui.screens.header.BreadcrumbHeader
import com.bespectacledcyborg.remoteexplorer.ui.screens.header.ExplorerButtons
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import kotlinx.coroutines.flow.combine
import java.net.URLConnection
import java.nio.file.Paths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    explorerViewModel: ExplorerViewModel,
    navHostController: NavHostController,
) {
    val context = LocalContext.current

    val connectionName by explorerViewModel.connectionNameState.collectAsState()
    val pathState by explorerViewModel.pathState.collectAsState()
    val filteredList = combine(
        explorerViewModel.listState,
        explorerViewModel.searchState,
        explorerViewModel.filterState,
        explorerViewModel.sortState,
        explorerViewModel.pathState,
    ) { list, search, filter, sort, path ->
        var finalList = list[path].orEmpty().sortedWith(explorerViewModel.getSortFunction(sort))
        if (filter.isNotEmpty()) {
            finalList = finalList.filter {
                val iconType = Utils.guessIconType(it.filename)
                filter.contains(iconType)
            }
        }

        if (search.isNotEmpty()) {
            finalList.filter {
                it.filename.contains(search, ignoreCase = true)
            }
        }

        finalList
    }.collectAsState(emptyList())
    val thumbnails by explorerViewModel.thumbnailsState.collectAsState()
    val columnCount by explorerViewModel.columnCountState.collectAsState()

    val handleItemClick = { item: Item<LocalFile> ->
        {
            val path = Paths.get(item.item.parentPath, item.item.filename).toString()
            when (item.type) {
                FileType.FOLDER -> {
                    explorerViewModel.changePath(path)
                }

                FileType.FILE -> {
                    val isBrowsable = { item: LocalFile ->
                        Utils.guessIconType(item.filename) != IconType.VIDEO
                    }
                    if (isBrowsable(item.item)) {
                        val browsableImages = filteredList.value.filter { isBrowsable(it) }
                        val initialBrowsableImage = browsableImages.indexOf(item.item)
                        explorerViewModel.browseImages(browsableImages, initialBrowsableImage)
                        navHostController.navigate(Route.ImageBrowserRoute)
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW)
                        val mimeType = URLConnection.guessContentTypeFromName(item.name)
                        val url = explorerViewModel.getUrl(path)
                        intent.setDataAndType(Uri.parse(url), mimeType)

                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "No application found for file type: $mimeType",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                else -> {}
            }
        }
    }

    val handleLongItemClick = { item: Item<LocalFile> ->
        {
            Toast.makeText(context, item.name, Toast.LENGTH_SHORT).show()
        }
    }

    val onOpenTagsScreen = { navHostController.navigate(Route.TagsRoute) }

    BackHandler {
        explorerViewModel.goBack()
    }

    val search by explorerViewModel.searchState.collectAsState()
    Scaffold(
        topBar = {
            MediumTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    BreadcrumbHeader(
                        rootPath = connectionName,
                        currentPath = pathState,
                        modifier = Modifier.wrapContentSize(Alignment.CenterStart),
                        onBreadcrumbClick = { path ->
                            explorerViewModel.search("")
                            if (path == "home") {
                                navHostController.navigate(Route.ConnectionsRoute)
                            } else {
                                explorerViewModel.changePath(path)
                            }
                        },
                        search = search
                    )
                },

                navigationIcon = {
                    IconButton(onClick = { explorerViewModel.goBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Localized description"
                        )
                    }
                },
                actions = {
                    ExplorerButtons(explorerViewModel, onOpenTagsScreen)
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
                    getImageUrl = { fullPath, thumbnail -> explorerViewModel.getUrl(fullPath, thumbnail) },
                    items = filteredList.value.map {
                        Item(
                            name = it.filename,
                            type = it.type,
                            thumbnails = thumbnails.getOrDefault(
                                Paths.get(
                                    it.parentPath,
                                    it.filename
                                ).toString(), emptyList()
                            ),
                            item = it,
                        )
                    },
                    handleItemClick = handleItemClick,
                    handleLongItemClick = handleLongItemClick,
                    handleRefresh = { explorerViewModel.forceRefresh() },
                    columnCount = columnCount,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}