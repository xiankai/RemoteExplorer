package com.bespectacledcyborg.remoteexplorer.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bespectacledcyborg.remoteexplorer.ui.screens.ConnectionDetailsScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.ConnectionsScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.ExplorerScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.ImageBrowserScreen
import com.bespectacledcyborg.remoteexplorer.ui.screens.TagsScreen
import com.bespectacledcyborg.remoteexplorer.ui.theme.RemoteExplorerTheme
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import kotlinx.serialization.Serializable

@Serializable
sealed class Route {
    @Serializable object ConnectionsRoute
    @Serializable object ConnectionDetailsRoute
    @Serializable object ExplorerRoute
    @Serializable object ImageBrowserRoute
    @Serializable object TagsRoute
}

@Composable
fun MainView(
    explorerViewModel: ExplorerViewModel,
) {
    val context = LocalContext.current
    val navHostController = rememberNavController()

    LaunchedEffect("") {
        explorerViewModel.toast.collect { message ->
            if(message.isNotEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    RemoteExplorerTheme {
        Routes(explorerViewModel, navHostController)
    }
}

@Composable
fun Routes(
    explorerViewModel: ExplorerViewModel,
    navHostController: NavHostController,
) {
    NavHost(navController = navHostController, startDestination = Route.ConnectionsRoute) {
        composable<Route.ConnectionsRoute> {
            ConnectionsScreen(
                explorerViewModel = explorerViewModel,
                navHostController = navHostController,
            )
        }
        composable<Route.ConnectionDetailsRoute> {
            ConnectionDetailsScreen(
                connection = explorerViewModel.connectionState.collectAsState().value,
                onSave = {
                    explorerViewModel.addConnection(it)
                    navHostController.navigate(Route.ConnectionsRoute)
                },
                onTest = {
                    explorerViewModel.testConnection(it)
                },
                onCancel = { navHostController.navigate(Route.ConnectionsRoute) },
                onDelete = {
                    explorerViewModel.deleteConnection(it)
                    navHostController.navigate(Route.ConnectionsRoute)
                },
            )
        }
        composable<Route.ExplorerRoute> {
            ExplorerScreen(
                explorerViewModel = explorerViewModel,
                navHostController = navHostController,
            )
        }
        composable<Route.ImageBrowserRoute> {
            ImageBrowserScreen(
                explorerViewModel = explorerViewModel,
            )
        }
        composable<Route.TagsRoute> {
            TagsScreen(
                explorerViewModel = explorerViewModel,
                navHostController = navHostController,
            )
        }
    }
}