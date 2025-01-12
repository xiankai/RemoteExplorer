package com.example.remoteexplorer.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.data.db.LocalFile
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import java.net.URLConnection
import java.nio.file.Paths

@Composable
fun ExplorerScreen(
    explorerViewModel: ExplorerViewModel,
) {
    val context = LocalContext.current

    val list = explorerViewModel.listState.collectAsState()
    val thumbnails = explorerViewModel.thumbnailsState.collectAsState()

    val handleItemClick = { item: Item<LocalFile> -> {
        val path = Paths.get(item.item.parentPath, item.item.filename).toString()
        when (item.type) {
            FileType.FOLDER -> {
                explorerViewModel.changePath(path)
            }
            FileType.FILE -> {
                val intent = Intent(Intent.ACTION_VIEW)
                val mimeType = URLConnection.guessContentTypeFromName(item.name)
                val url = explorerViewModel.getUrl(path)
                intent.setDataAndType(Uri.parse(url), mimeType)

                context.startActivity(intent)
            }
            else -> {}
        }
    } }

    val handleLongItemClick = { item: Item<LocalFile> -> {
        Toast.makeText(context, item.name, Toast.LENGTH_SHORT).show()
    } }

    Explorer(
        items = list.value.map { Item(
            name = it.filename,
            type = it.type,
            urls = thumbnails.value.getOrDefault(it.filename, emptyList()).map { explorerViewModel.getUrl(it) },
            item = it,
        ) },
        handleItemClick = handleItemClick,
        handleLongItemClick = handleLongItemClick,
    )
}