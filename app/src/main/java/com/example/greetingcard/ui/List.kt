package com.example.greetingcard.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import kotlinx.coroutines.Dispatchers

@Composable
fun List(
    listViewModel: ListViewModel = ListViewModel(Dispatchers.IO)
) {
    val context = LocalContext.current

    BackHandler {
        listViewModel.goBack()
    }

    val listState by listViewModel.listState.collectAsState()
    val pathState by listViewModel.pathState.collectAsState()


    Column(
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.TopStart),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BreadcrumbHeader(
            currentPath = pathState,
            modifier = Modifier.wrapContentSize(Alignment.CenterStart),
            onBreadcrumbClick = { path -> listViewModel.changePath(path) },
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 128.dp),
        ) {
            items(listState) { file ->
                ListItem(
                    file = file,
                    handleChangePath = { (clickedItem) -> listViewModel.changePath(clickedItem) },
                    handleOpenItem = { (clickedItem) ->
                        val url = listViewModel.getUrl(clickedItem)

                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(Uri.parse(url), "image/*")
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                        context.startActivity(intent)
                    },

                )
            }
        }
    }
}