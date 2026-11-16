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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.ui.components.CoverChoiceSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.ViewTotals
import com.bespectacledcyborg.remoteexplorer.data.newestAdditionsPath
import com.bespectacledcyborg.remoteexplorer.data.tagPrefix
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.data.PLAYBACK_TYPES
import com.bespectacledcyborg.remoteexplorer.data.SortType
import com.bespectacledcyborg.remoteexplorer.data.views.ExternalPlaybackService
import com.bespectacledcyborg.remoteexplorer.ui.components.Explorer
import com.bespectacledcyborg.remoteexplorer.ui.components.Item
import com.bespectacledcyborg.remoteexplorer.ui.components.addedOn
import com.bespectacledcyborg.remoteexplorer.ui.components.clusteredByAddedOn
import com.bespectacledcyborg.remoteexplorer.ui.components.clusteredByParent
import com.bespectacledcyborg.remoteexplorer.ui.components.clusteredByParentInOrder
import com.bespectacledcyborg.remoteexplorer.ui.components.parentGroupOf
import com.bespectacledcyborg.remoteexplorer.data.GroupMode
import java.time.LocalDate
import java.time.ZoneId
import com.bespectacledcyborg.remoteexplorer.ui.components.Route
import com.bespectacledcyborg.remoteexplorer.ui.components.TagStrip
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import com.bespectacledcyborg.remoteexplorer.ui.components.resolveColumnCount
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
        explorerViewModel.groupModeState,
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
        val groupMode = values[6] as GroupMode
        var finalList = list[path].orEmpty().sortedWith(explorerViewModel.getSortFunction(sort, views))
        if (filter.isNotEmpty()) {
            finalList = finalList.filter {
                val iconType = Utils.guessIconType(it.filename)
                filter.contains(iconType)
            }
        }

        if (search.isNotEmpty()) {
            finalList = finalList.filter {
                it.filename.contains(search, ignoreCase = true)
            }
        }

        finalList = when {
            path.startsWith(tagPrefix) -> clusteredByParent(finalList)
            groupMode == GroupMode.BY_DATE -> clusteredByAddedOn(finalList, LocalDate.now(), ZoneId.systemDefault())
            groupMode == GroupMode.BY_PARENT_FOLDER -> clusteredByParentInOrder(finalList)
            else -> finalList
        }

        finalList
    }.collectAsState(emptyList())
    val thumbnails by explorerViewModel.thumbnailsState.collectAsState()
    val listingViews by explorerViewModel.listingViewsState.collectAsState()
    val captionedFolders by explorerViewModel.captionedFoldersState.collectAsState()
    val appliedCovers by explorerViewModel.appliedCoversState.collectAsState()
    val columns by explorerViewModel.columnsState.collectAsState()
    val isRefreshing by explorerViewModel.isRefreshingState.collectAsState()
    val groupMode by explorerViewModel.groupModeState.collectAsState()
    val connections by explorerViewModel.connectionsState.collectAsState()
    val browsingConnection by explorerViewModel.connectionState.collectAsState()
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()

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
                            if (Utils.guessIconType(item.name) in PLAYBACK_TYPES) {
                                ExternalPlaybackService.start(context, item.name)
                            }
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

    var coverFolder by remember { mutableStateOf<LocalFile?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val dismissCoverSheetOnStop = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) coverFolder = null
        }
        lifecycleOwner.lifecycle.addObserver(dismissCoverSheetOnStop)
        onDispose { lifecycleOwner.lifecycle.removeObserver(dismissCoverSheetOnStop) }
    }
    val handleLongItemClick = { item: Item<LocalFile> ->
        {
            if (item.type == FileType.FOLDER) {
                coverFolder = item.item
            } else {
                Toast.makeText(context, item.name, Toast.LENGTH_SHORT).show()
            }
        }
    }

    coverFolder?.let { folder ->
        val path = folder.fullPath()
        var current by remember(path) { mutableStateOf<CoverMode?>(null) }
        var keepsFirstPage by remember(path) { mutableStateOf(false) }
        LaunchedEffect(path) {
            keepsFirstPage = explorerViewModel.folderKeepsFirstPage(path)
            current = explorerViewModel.coverChoiceFor(path)
        }
        CoverChoiceSheet(
            folderName = folder.filename,
            current = current,
            keepsFirstPage = keepsFirstPage,
            onChoose = { mode ->
                explorerViewModel.chooseCover(path, mode)
                coverFolder = null
            },
            onDismiss = { coverFolder = null },
        )
    }

    val onOpenTagsScreen = { navHostController.navigate(Route.TagsRoute) }

    val navigationStack by explorerViewModel.navigationStackState.collectAsState()
    val atRoot = navigationStack.size <= 1
    val atTag = navigationStack.lastOrNull()?.startsWith(tagPrefix) == true  // stack-reviewer-ignore: the same reserved-prefix test ExplorerViewModel already uses to choose between a tag query and a folder listing, so a folder named "Tag: …" was a tag before this change too
    val returnToConnections = {
        explorerViewModel.leaveConnection()
        if (!navHostController.popBackStack(Route.ConnectionsRoute, inclusive = false)) {
            navHostController.navigate(Route.ConnectionsRoute)
        }
    }
    val returnToTags = {
        explorerViewModel.goBack()
        if (!navHostController.popBackStack(Route.TagsRoute, inclusive = false)) {
            navHostController.navigate(Route.TagsRoute)
        }
    }

    val openedFromTagList =  // stack-reviewer-ignore: TagsScreen is the only place that navigates to ExplorerRoute on top of TagsRoute, so the previous entry tells the two entry points apart; otherwise back is goBack(), which pops the view model's stack to the folder the tag was opened from (checked on the emulator)
        navHostController.previousBackStackEntry?.destination?.hasRoute<Route.TagsRoute>() == true
    val backToTags = atTag && openedFromTagList

    val backLeavesExplorer = !atTag && atRoot
    BackHandler(enabled = !backLeavesExplorer) {
        if (backToTags) returnToTags() else explorerViewModel.goBack()
    }
    BackHandler(enabled = backLeavesExplorer) {
        if (navHostController.previousBackStackEntry?.destination?.hasRoute<Route.ConnectionsRoute>() == true) {
            explorerViewModel.leaveConnection()
        }
        navHostController.popBackStack()
    }

    val folderTags by explorerViewModel.folderTagsState.collectAsState()

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
                        pseudoFolderLabel = when {
                            openTagLabel != null -> "$tagPrefix$openTagLabel"
                            pathState == newestAdditionsPath -> "Newest additions"
                            else -> null
                        },
                    )
                },

                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            backToTags -> returnToTags()
                            atRoot -> returnToConnections()
                            else -> explorerViewModel.goBack()
                        }
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
                            captioned = it.fullPath() in captionedFolders,
                            cover = appliedCovers[it.fullPath()] ?: CoverMode.DEFAULT,
                            dimmed = pathState == newestAdditionsPath &&
                                (listingViews[it.fullPath()] ?: ViewTotals.NONE) != ViewTotals.NONE,
                        )
                    },
                    handleItemClick = handleItemClick,
                    handleLongItemClick = handleLongItemClick,
                    handleRefresh = { explorerViewModel.forceRefresh() },
                    columnCount = resolveColumnCount(columns),
                    isRefreshing = isRefreshing,
                    scrollKey = pathState,
                    initialScroll = explorerViewModel.scrollPositionToRestore(),
                    onScrollPositionChanged = { explorerViewModel.recordScrollPosition(pathState, it) },
                    groupOf = when {
                        pathState.startsWith(tagPrefix) -> { item -> item.item.parentPath }
                        groupMode == GroupMode.BY_DATE -> { item ->
                            addedOn(item.item.lastModified, today, zone).label
                        }
                        groupMode == GroupMode.BY_PARENT_FOLDER -> { item -> parentGroupOf(item.item) }
                        else -> null
                    },
                    groupLabel = when (groupMode) {
                        GroupMode.BY_DATE -> { group -> group }
                        GroupMode.BY_PARENT_FOLDER -> { group ->
                            filteredList.value.firstOrNull { parentGroupOf(it) == group }
                                ?.let { head ->
                                    val name = connections.firstOrNull { it.id == head.connectionId }?.name
                                        ?: connectionName
                                    when {
                                        head.parentPath.isEmpty() -> name
                                        head.connectionId == browsingConnection?.id -> head.parentPath
                                        else -> "$name · ${head.parentPath}"
                                    }
                                }
                                ?: group
                        }
                        GroupMode.UNGROUPED -> { group -> group.ifEmpty { connectionName } }
                    },
                    onGroupClick = when (groupMode) {
                        GroupMode.BY_DATE -> null
                        GroupMode.BY_PARENT_FOLDER -> { group ->
                            filteredList.value.firstOrNull { parentGroupOf(it) == group }
                                ?.let { explorerViewModel.changePath(it.parentPath) }
                        }
                        GroupMode.UNGROUPED -> { group -> explorerViewModel.changePath(group) }
                    },
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
        bottomBar = {
            TagStrip(
                tags = folderTags,
                onOpenTag = { explorerViewModel.getFilesWithTag(LocalTag(it.tag)) },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}