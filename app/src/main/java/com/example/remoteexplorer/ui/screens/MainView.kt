package com.example.remoteexplorer.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import com.example.remoteexplorer.ui.screens.ConnectionDetailsScreen
import com.example.remoteexplorer.ui.screens.ConnectionsScreen
import com.example.remoteexplorer.ui.screens.ExplorerScreen
import com.example.remoteexplorer.ui.screens.ImageBrowserScreen
import com.example.remoteexplorer.ui.screens.TagsScreen
import com.example.remoteexplorer.ui.theme.RemoteExplorerTheme
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
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
                onConnect = {
                    explorerViewModel.connect(it)
                    navHostController.navigate(Route.ExplorerRoute)
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
        dialog<Route.TagsRoute> {
            TagsScreen(
                explorerViewModel = explorerViewModel,
                onDismissDialog = { navHostController.popBackStack() },
            )
        }
    }
}