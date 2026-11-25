package com.bespectacledcyborg.remoteexplorer.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.nio.file.Paths
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
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.ui.components.ConfirmActionDialog
import com.bespectacledcyborg.remoteexplorer.ui.components.CoverChoiceSheet
import com.bespectacledcyborg.remoteexplorer.ui.components.EntryActionSheet
import com.bespectacledcyborg.remoteexplorer.ui.components.MoveTargetDialog
import com.bespectacledcyborg.remoteexplorer.ui.components.NameEntryDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.ViewTotals
import com.bespectacledcyborg.remoteexplorer.data.createdAt
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
import com.bespectacledcyborg.remoteexplorer.ui.components.rememberToday
import java.time.ZoneId
import com.bespectacledcyborg.remoteexplorer.ui.components.gallerySwipe
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
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.EntryDialog
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ListingCreated
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
    val zone = ZoneId.systemDefault()
    val today = rememberToday(zone)
    val filteredList = remember(explorerViewModel, today, zone) {  // stack-reviewer-ignore: the view model is the activity's single instance and tagPrefix is a const; the sort, path, search and group mode all arrive as flow values inside the combine, so only the date needs to be a key
        combine(
            explorerViewModel.listState,
            explorerViewModel.searchState,
            explorerViewModel.filterState,
            explorerViewModel.sortState,
            explorerViewModel.pathState,
            explorerViewModel.listingViewsState,
            explorerViewModel.groupModeState,
            explorerViewModel.listingCreatedState,
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
            val listingCreated = values[7] as ListingCreated
            val created = listingCreated.byPath
            val folder = list[path].orEmpty().takeIf { listingCreated.readyFor(it, sort, path) }.orEmpty()
            val narrowed = folder
                .sortedWith(explorerViewModel.getSortFunction(sort, views, created))
                .keepingTypes(filter)
                .matchingSearch(search)
            ShownListing(
                entries = when {
                    path.startsWith(tagPrefix) -> clusteredByParent(narrowed)
                    groupMode == GroupMode.BY_DATE -> clusteredByAddedOn(narrowed, created, today, zone)
                    groupMode == GroupMode.BY_PARENT_FOLDER -> clusteredByParentInOrder(narrowed)
                    else -> narrowed
                },
                emptyMessage = emptyResultMessage(search, filter, folder.isEmpty(), narrowed.isEmpty()),
            )
        }
    }.collectAsState(ShownListing.NONE)
    val thumbnails by explorerViewModel.thumbnailsState.collectAsState()
    val listingViews by explorerViewModel.listingViewsState.collectAsState()
    val listingCreated by explorerViewModel.listingCreatedState.collectAsState()
    val captionedFolders by explorerViewModel.captionedFoldersState.collectAsState()
    val appliedCovers by explorerViewModel.appliedCoversState.collectAsState()
    val columns by explorerViewModel.columnsState.collectAsState()
    val isRefreshing by explorerViewModel.isRefreshingState.collectAsState()
    val groupMode by explorerViewModel.groupModeState.collectAsState()
    val connections by explorerViewModel.connectionsState.collectAsState()
    val browsingConnection by explorerViewModel.connectionState.collectAsState()
    val parentGroupHeads = remember(filteredList.value) {
        filteredList.value.entries.distinctBy(::parentGroupOf).associateBy(::parentGroupOf)
    }

    var pendingPlaybackName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlaybackPath by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val name = pendingPlaybackName
        val path = pendingPlaybackPath
        pendingPlaybackName = null
        pendingPlaybackPath = null
        if (name != null && path != null) {
            handOffToExternalApp(context, name, explorerViewModel.getUrl(path))
        }
    }

    val handleItemClick = { item: Item<LocalFile> ->
        {
            when (item.type) {
                FileType.FOLDER -> {
                    explorerViewModel.openFolder(item.item.fullPath(), filteredList.value.entries)
                }

                FileType.FILE -> {
                    if (item.item.isBrowsable()) {
                        val browsableImages = filteredList.value.entries.filter { it.isBrowsable() }
                        val initialBrowsableImage = browsableImages.indexOf(item.item)
                        explorerViewModel.browseImages(browsableImages, initialBrowsableImage)
                        navHostController.navigate(Route.ImageBrowserRoute)
                    } else {
                        val path = item.item.fullPath()
                        val isPlayback = Utils.guessIconType(item.name) in PLAYBACK_TYPES
                        if (isPlayback && !context.canPostNotifications()) {
                            pendingPlaybackName = item.name
                            pendingPlaybackPath = path
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            handOffToExternalApp(context, item.name, explorerViewModel.getUrl(path))
                        }
                    }
                }

                else -> {}
            }
        }
    }

    var coverFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalActivity.current
    DisposableEffect(lifecycleOwner, activity) {
        val dismissCoverSheetOnStop = LifecycleEventObserver { _, event ->
            val leavingForGood = activity?.isChangingConfigurations != true // stack-reviewer-ignore: ExplorerScreen is only hosted by MainActivity, so activity is never null; if it were, #162's dismiss-on-background is the safe default
            if (event == Lifecycle.Event.ON_STOP && leavingForGood) coverFolderPath = null
        }
        lifecycleOwner.lifecycle.addObserver(dismissCoverSheetOnStop)
        onDispose { lifecycleOwner.lifecycle.removeObserver(dismissCoverSheetOnStop) }
    }
    var actionPath by rememberSaveable { mutableStateOf<String?>(null) }
    val handleLongItemClick = { item: Item<LocalFile> ->
        { actionPath = item.item.fullPath() }
    }

    val actionEntry = actionPath?.let { path ->
        filteredList.value.entries.firstOrNull { it.fullPath() == path }
    }
    actionEntry?.let { entry ->
        EntryActionSheet(
            entryName = entry.filename,
            isFolder = entry.type == FileType.FOLDER,
            onRename = {
                explorerViewModel.renameEntry(entry)
                actionPath = null
            },
            onMove = {
                explorerViewModel.moveEntry(entry)
                actionPath = null
            },
            onCover = {
                coverFolderPath = entry.fullPath()
                actionPath = null
            },
            onDismiss = { actionPath = null },
        )
    }

    val entryDialog by explorerViewModel.entryDialogState.collectAsState()
    when (val dialog = entryDialog) {
        null -> Unit
        is EntryDialog.NewFolder -> NameEntryDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onConfirm = explorerViewModel::createFolder,
            onDismiss = explorerViewModel::dismissEntryDialog,
        )
        is EntryDialog.Rename -> NameEntryDialog(
            title = "Rename",
            initial = dialog.request.path.substringAfterLast('/'),
            confirmLabel = "Rename",
            onConfirm = explorerViewModel::renameEntryTo,
            onDismiss = explorerViewModel::dismissEntryDialog,
        )
        is EntryDialog.Move -> MoveTargetDialog(
            rootPath = "",
            foldersIn = { explorerViewModel.foldersIn(it) },
            onPick = explorerViewModel::moveEntryInto,
            onDismiss = explorerViewModel::dismissEntryDialog,
        )
        is EntryDialog.ConfirmNas -> ConfirmActionDialog(
            title = "Change it on the share?",
            text = dialog.request.path.substringAfterLast('/') +
                " only exists on the share, so there is no Syncthing copy to undo this.",
            confirmLabel = "Change it",
            onConfirm = explorerViewModel::confirmNasWrite,
            onDismiss = explorerViewModel::dismissEntryDialog,
        )
        is EntryDialog.ConfirmLarge -> ConfirmActionDialog(
            title = "That folder is large",
            text = "It holds " + dialog.size.files + " files and " +
                (dialog.size.bytes / (1024 * 1024 * 1024)) +
                " GB, so Syncthing may have to transfer it again.",
            confirmLabel = "Continue",
            onConfirm = explorerViewModel::confirmLargeMove,
            onDismiss = explorerViewModel::dismissEntryDialog,
        )
    }

    coverFolderPath?.let { path ->
        var current by remember(path) { mutableStateOf<CoverMode?>(null) }
        var keepsFirstPage by remember(path) { mutableStateOf(false) }
        LaunchedEffect(path) {
            keepsFirstPage = explorerViewModel.folderKeepsFirstPage(path)
            current = explorerViewModel.coverChoiceFor(path)
        }
        CoverChoiceSheet(
            folderName = Paths.get(path).fileName.toString(),
            current = current,
            keepsFirstPage = keepsFirstPage,
            onChoose = { mode ->
                explorerViewModel.chooseCover(path, mode)
                coverFolderPath = null
            },
            onDismiss = { coverFolderPath = null },
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
    val search by explorerViewModel.searchState.collectAsState()
    val searchActive = search.isNotEmpty()
    BackHandler(enabled = searchActive) { explorerViewModel.clearSearch() }

    val folderTags by explorerViewModel.folderTagsState.collectAsState()

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
                            searchActive -> explorerViewModel.clearSearch()
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
                    .gallerySwipe(explorerViewModel::swipeGallery)
                    .wrapContentSize(Alignment.TopStart),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                filteredList.value.emptyMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                Explorer(
                    getImageUrl = { fullPath, thumbnail ->
                        explorerViewModel.getUrl(
                            fullPath,
                            thumbnail
                        )
                    },
                    items = filteredList.value.entries.map {
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
                            addedOn(item.item.createdAt(listingCreated.byPath), today, zone).label
                        }
                        groupMode == GroupMode.BY_PARENT_FOLDER -> { item -> parentGroupOf(item.item) }
                        else -> null
                    },
                    groupLabel = when (groupMode) {  // stack-reviewer-ignore: exhaustive on purpose; a new GroupMode must fail to compile here until its header label and tap are decided, rather than silently fall back
                        GroupMode.BY_DATE -> { group -> group }
                        GroupMode.BY_PARENT_FOLDER -> { group ->
                            parentGroupHeads[group]
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
                    onGroupClick = when (groupMode) {  // stack-reviewer-ignore: exhaustive on purpose; a new GroupMode must fail to compile here until its header label and tap are decided, rather than silently fall back
                        GroupMode.BY_DATE -> null
                        GroupMode.BY_PARENT_FOLDER -> { group ->
                            parentGroupHeads[group]?.let { explorerViewModel.changePath(it.parentPath) }
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

private fun Context.canPostNotifications(): Boolean =
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun handOffToExternalApp(context: Context, name: String, url: String) {
    val mimeType = URLConnection.guessContentTypeFromName(name)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(url.toUri(), mimeType)
    try {
        context.startActivity(intent)
        if (Utils.guessIconType(name) in PLAYBACK_TYPES) {
            ExternalPlaybackService.start(context, name)
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No application found for file type: $mimeType", Toast.LENGTH_SHORT).show()
    }
}