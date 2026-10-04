package com.bespectacledcyborg.remoteexplorer.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.ViewTotals
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.SortType
import com.bespectacledcyborg.remoteexplorer.ui.components.Explorer
import com.bespectacledcyborg.remoteexplorer.ui.components.Item
import com.bespectacledcyborg.remoteexplorer.ui.components.Route
import com.bespectacledcyborg.remoteexplorer.ui.screens.header.BreadcrumbHeader
import com.bespectacledcyborg.remoteexplorer.ui.screens.header.ExplorerButtons
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import kotlinx.coroutines.flow.combine
import java.net.URLConnection

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
        explorerViewModel.listingViewsState,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val list = values[0] as Map<String, List<LocalFile>>
        val search = values[1] as String
        @Suppress("UNCHECKED_CAST")
        val filter = values[2] as List<IconType>
        val sort = values[3] as SortType
        val path = values[4] as String
        @Suppress("UNCHECKED_CAST")
        val views = values[5] as Map<String, ViewTotals>
        var finalList = list[path].orEmpty().sortedWith(explorerViewModel.getSortFunction(sort, views))
        if (filter.isNotEmpty()) {
            finalList = finalList.filter {
                val iconType = Utils.guessIconType(it.filename)
                filter.contains(iconType)
            }
        }

        if (search.isNotEmpty()) {
            // The result used to be discarded, so this filter did nothing at all.
            finalList = finalList.filter {
                it.filename.contains(search, ignoreCase = true)
            }
        }

        finalList
    }.collectAsState(emptyList())
    val thumbnails by explorerViewModel.thumbnailsState.collectAsState()
    val columnCount by explorerViewModel.columnCountState.collectAsState()
    val isRefreshing by explorerViewModel.isRefreshingState.collectAsState()

    val handleItemClick = { item: Item<LocalFile> ->
        {
            when (item.type) {
                FileType.FOLDER -> {
                    explorerViewModel.changePath(item.item.fullPath())
                }

                FileType.FILE -> {
                    if (item.item.isBrowsable()) {
                        val browsableImages = filteredList.value.filter { it.isBrowsable() }
                        val initialBrowsableImage = browsableImages.indexOf(item.item)
                        explorerViewModel.browseImages(browsableImages, initialBrowsableImage)
                        navHostController.navigate(Route.ImageBrowserRoute)
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW)
                        val mimeType = URLConnection.guessContentTypeFromName(item.name)
                        val url = explorerViewModel.getUrl(item.item.fullPath())
                        intent.setDataAndType(url.toUri(), mimeType)

                        try {
                            context.startActivity(intent)
                            // A launch, which is all the app can know here. How much was
                            // watched comes from the bytes the player then pulls through
                            // the loopback server.
                            explorerViewModel.recordOpen(item.item)
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

    val navigationStack by explorerViewModel.navigationStackState.collectAsState()
    val atRoot = navigationStack.size <= 1
    val returnToConnections = {
        if (!navHostController.popBackStack(Route.ConnectionsRoute, inclusive = false)) {
            navHostController.navigate(Route.ConnectionsRoute)
        }
    }

    BackHandler(enabled = !atRoot) {
        explorerViewModel.goBack()
    }

    val search by explorerViewModel.searchState.collectAsState()
    val openTagLabel by explorerViewModel.openTagLabelState.collectAsState()
    Scaffold(
        topBar = {
            MediumTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    // on-container, not primary: primary on primaryContainer is two tints of one
                    // hue, and in the dark scheme the title all but disappeared into the bar.
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                title = {
                    BreadcrumbHeader(
                        rootPath = connectionName,
                        currentPath = pathState,
                        modifier = Modifier.wrapContentSize(Alignment.CenterStart),
                        onBreadcrumbClick = { path ->
                            explorerViewModel.search("")
                            if (path == "home") {
                                returnToConnections()
                            } else {
                                explorerViewModel.changePath(path)
                            }
                        },
                        search = search,
                        tagLabel = openTagLabel,
                    )
                },

                navigationIcon = {
                    IconButton(onClick = {
                        if (atRoot) returnToConnections() else explorerViewModel.goBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
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
                    getImageUrl = { fullPath, thumbnail ->
                        explorerViewModel.getUrl(
                            fullPath,
                            thumbnail
                        )
                    },
                    items = filteredList.value.map {
                        Item(
                            name = it.filename,
                            type = it.type,
                            thumbnails = thumbnails.getOrDefault(
                                it.fullPath(), emptyList()
                            ),
                            item = it,
                        )
                    },
                    handleItemClick = handleItemClick,
                    handleLongItemClick = handleLongItemClick,
                    handleRefresh = { explorerViewModel.forceRefresh() },
                    columnCount = columnCount,
                    isRefreshing = isRefreshing,
                    scrollKey = pathState,
                    onVisibleItemsChanged = { visible ->
                        explorerViewModel.requestCovers(
                            visible
                                .filter { it.type == FileType.FOLDER }
                                .map { it.item.fullPath() }
                        )
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}