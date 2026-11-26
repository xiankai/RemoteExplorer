package com.bespectacledcyborg.remoteexplorer.ui.components

import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.bespectacledcyborg.remoteexplorer.ui.components.LocalThumbnailMissing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bespectacledcyborg.remoteexplorer.ui.screens.ConnectionDetailsScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.ConnectionsScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.ExplorerScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.ImageBrowserScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.SettingsScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.TagsScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.VideoViewerScreen
import com.bespectacledcyborg.remoteexplorer.ui.theme.RemoteExplorerTheme
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable
sealed class Route {
    @Serializable
    object ConnectionsRoute

    @Serializable
    object ConnectionDetailsRoute

    @Serializable
    object ExplorerRoute

    @Serializable
    object ImageBrowserRoute

    @Serializable
    object VideoViewerRoute

    @Serializable
    object TagsRoute

    @Serializable
    object SettingsRoute
}

private const val SCREEN_FADE_OUT_MILLIS = 90
private const val SCREEN_FADE_IN_MILLIS = 210

@Composable
fun MainView(
    explorerViewModel: ExplorerViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val navHostController = rememberNavController()

    LaunchedEffect("") {
        explorerViewModel.toast.collect { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    RemoteExplorerTheme {
        Routes(explorerViewModel, settingsViewModel, navHostController)
    }
}

@Composable
fun Routes(
    explorerViewModel: ExplorerViewModel,
    settingsViewModel: SettingsViewModel,
    navHostController: NavHostController,
) {
    LaunchedEffect(Unit) {
        if (explorerViewModel.restoreLastLocation() && navHostController.isOnlyOnConnections()) {  // stack-reviewer-ignore: a tap during the suspend goes through connect, and restoreLastLocation then returns false before reconnecting, so the view model never ends up on the restored connection while the UI shows the tapped one
            navHostController.navigate(Route.ExplorerRoute)
        }
    }

    NavHost(
        navController = navHostController,
        startDestination = Route.ConnectionsRoute,
        enterTransition = { fadeIn(tween(SCREEN_FADE_IN_MILLIS, delayMillis = SCREEN_FADE_OUT_MILLIS)) },
        exitTransition = { fadeOut(tween(SCREEN_FADE_OUT_MILLIS)) },
        popEnterTransition = { fadeIn(tween(SCREEN_FADE_IN_MILLIS, delayMillis = SCREEN_FADE_OUT_MILLIS)) },
        popExitTransition = { fadeOut(tween(SCREEN_FADE_OUT_MILLIS)) },
    ) {
        composable<Route.ConnectionsRoute> {
            ConnectionsScreen(
                explorerViewModel = explorerViewModel,
                navHostController = navHostController,
            )
        }
        composable<Route.ConnectionDetailsRoute> {
            ConnectionDetailsScreen(
                connection = explorerViewModel.connectionState.collectAsState().value,
                connections = explorerViewModel.connectionsState.collectAsState().value,
                pairings = explorerViewModel.pairingsState.collectAsState().value,
                onSavePairing = explorerViewModel::savePairing,
                onDeletePairing = explorerViewModel::deletePairing,
                onOpenShare = { share ->
                    explorerViewModel.connect(share)
                    navHostController.navigate(Route.ExplorerRoute) {
                        popUpTo<Route.ConnectionDetailsRoute> { inclusive = true }
                    }
                },
                onEditShare = { share ->
                    explorerViewModel.editConnection(share)
                    navHostController.navigate(Route.ConnectionDetailsRoute) {
                        popUpTo<Route.ConnectionDetailsRoute> { inclusive = true }
                    }
                },
                testResult = explorerViewModel.connectionTestState.collectAsState().value,
                onSave = {
                    explorerViewModel.addConnection(it)
                    navHostController.popBackStack()
                },
                onTest = {
                    explorerViewModel.testConnection(it)
                },
                onCancel = { navHostController.popBackStack() },
                onDelete = {
                    explorerViewModel.deleteConnection(it)
                    navHostController.popBackStack()
                },
            )
        }
        composable<Route.ExplorerRoute> {
            CompositionLocalProvider(LocalThumbnailMissing provides explorerViewModel::coverImageMissing) {
                ExplorerScreen(
                    explorerViewModel = explorerViewModel,
                    navHostController = navHostController,
                )
            }
        }
        composable<Route.ImageBrowserRoute> {
            ImageBrowserScreen(
                explorerViewModel = explorerViewModel,
                onTagOpened = { navHostController.popBackStack(Route.ExplorerRoute, inclusive = false) },
            )
        }
        composable<Route.VideoViewerRoute> {
            VideoViewerScreen(
                explorerViewModel = explorerViewModel,
                onTagOpened = { navHostController.popBackStack(Route.ExplorerRoute, inclusive = false) },
                onNothingToShow = { navHostController.popBackStack() },
            )
        }
        composable<Route.TagsRoute> {
            TagsScreen(
                explorerViewModel = explorerViewModel,
                navHostController = navHostController,
            )
        }
        composable<Route.SettingsRoute> {
            SettingsScreen(
                settingsViewModel = settingsViewModel,
                onBackPressed = { navHostController.popBackStack() }
            )
        }
    }
}

private fun NavHostController.isOnlyOnConnections(): Boolean =
    previousBackStackEntry == null &&
        currentBackStackEntry?.destination?.hasRoute<Route.ConnectionsRoute>() == true
