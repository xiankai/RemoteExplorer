package com.example.remoteexplorer.ui.screens.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun BreadcrumbHeader(
    rootPath: String,
    currentPath: String,
    modifier: Modifier,
    onBreadcrumbClick: (String) -> Unit,
) {
    val pathComponents = currentPath.split("/")
    val rootLabel = rootPath.ifEmpty { "Root" }

    Row {
        for (i in pathComponents.indices) {
            val crumb = pathComponents.subList(0, i + 1).joinToString("/")
            val display = crumb.split("/").last()
            Text(
                text = "/ $rootLabel / ",
                modifier = modifier
                    .clickable(
                        onClick = {
                            onBreadcrumbClick("")
                        }
                    )
            )

            Text(
                text = display,
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
