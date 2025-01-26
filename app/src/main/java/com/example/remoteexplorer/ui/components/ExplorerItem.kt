package com.example.remoteexplorer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerItem(
    explorerViewModel: ExplorerViewModel,
    name: String,
    type: FileType,
    thumbnails: List<String>,
    handleItemClick: () -> Unit,
    handleLongItemClick: () -> Unit,
    ) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                onClick = handleItemClick,
                onLongClick = handleLongItemClick,
            )
        ,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        ExplorerItemIcon(
            explorerViewModel,
            name,
            type,
            thumbnails,
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .padding(8.dp)
                .weight(1f)
        )
        Spacer(modifier = Modifier.width((8.dp)))
        Text(
            text = name,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}