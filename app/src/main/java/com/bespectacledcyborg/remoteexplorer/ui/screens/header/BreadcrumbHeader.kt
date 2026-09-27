package com.bespectacledcyborg.remoteexplorer.ui.screens.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bespectacledcyborg.remoteexplorer.data.tagPrefix

@Composable
fun BreadcrumbHeader(
    rootPath: String,
    currentPath: String,
    modifier: Modifier,
    onBreadcrumbClick: (String) -> Unit,
    search: String,
    // Set when currentPath is a tag. A tag is one crumb, not a path: split on "/" it made
    // `clothes/pantyhose` two crumbs, the first of which opened a tag called `clothes`.
    tagLabel: String? = null,
) {
    val pathComponents =
        if (tagLabel != null) emptyList() else currentPath.split("/").filter { it.isNotEmpty() }
    val rootLabel = rootPath.ifEmpty { "Root" }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
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

        if (search.isNotBlank()) {
            Text(
                text = "» Search: $search",
            )
        }

        if (tagLabel != null) {
            Text(text = "» $tagPrefix$tagLabel")
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
