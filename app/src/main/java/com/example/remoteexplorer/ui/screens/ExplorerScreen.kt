package com.example.remoteexplorer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.example.remoteexplorer.data.File
import com.example.remoteexplorer.ui.components.Explorer
import com.example.remoteexplorer.ui.components.Item
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

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
                val url = explorerViewModel.getUrl(item.url)
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setAction(Intent.ACTION_VIEW)
                intent.setDataAndType(Uri.parse(url), "image/*")
//                        intent.setData(Uri.parse(url))
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                context.startActivity(intent)
            }
        }
    } }

    Explorer(
        items =list.value.map { Item(
            name = it.filename,
            type = "file",
            url = explorerViewModel.getUrl(it.fullPath),
            item = it
        ) },
        handleItemClick = handleItemClick
    )
}