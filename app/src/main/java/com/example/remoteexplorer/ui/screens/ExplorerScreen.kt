package com.example.remoteexplorer.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.example.remoteexplorer.data.File
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import java.net.URLConnection

@Composable
fun ExplorerScreen(
    explorerViewModel: ExplorerViewModel,
) {
    val context = LocalContext.current

    val list = explorerViewModel.listState.collectAsState()

    val handleItemClick = { item: Item<File> -> {
        when (item.type) {
            "folder" -> {
                explorerViewModel.changePath(item.url)
            }
            "file" -> {
                val intent = Intent(Intent.ACTION_VIEW)
                val mimeType = URLConnection.guessContentTypeFromName(item.name)
                intent.setDataAndType(Uri.parse(item.url), mimeType)

                context.startActivity(intent)
            }
        }
    } }

    val handleLongItemClick = { item: Item<File> -> {
        Toast.makeText(context, item.name, Toast.LENGTH_SHORT).show()
    } }

    Explorer(
        items = list.value.map { Item(
            name = it.filename,
            type = "file",
            url = explorerViewModel.getUrl(it.fullPath),
            item = it
        ) },
        handleItemClick = handleItemClick,
        handleLongItemClick = handleLongItemClick,
    )
}