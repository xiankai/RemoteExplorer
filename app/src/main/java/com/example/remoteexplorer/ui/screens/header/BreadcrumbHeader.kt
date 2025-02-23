package com.example.remoteexplorer.ui.screens.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun BreadcrumbHeader(
    rootPath: String,
    currentPath: String,
    modifier: Modifier,
    onBreadcrumbClick: (String) -> Unit,
    search: String?,
) {
    val pathComponents = currentPath.split("/").filter { it.isNotEmpty() }
    val rootLabel = rootPath.ifEmpty { "Root" }

    Row {
        Icon(
            Icons.Default.Home,
            contentDescription = "Home",
            modifier = Modifier.clickable(
                onClick = {
                    onBreadcrumbClick("home")
                }
            )
        )

        Text(
            text = "» $rootLabel",
            modifier = modifier
                .clickable(
                    onClick = {
                        onBreadcrumbClick("")
                    }
                )
        )

        if (!search.isNullOrBlank()) {
            Text(
                text = "» Search: $search",
            )
        }

        for (i in pathComponents.indices) {
            val crumb = pathComponents.subList(0, i + 1).joinToString("/")
            val display = crumb.split("/").last()

            Text(
                text = "» $display",
                modifier = modifier
                    .clickable(
                        onClick = {
                            onBreadcrumbClick(crumb)
                        }
                    )
            )
        }
    }
}
