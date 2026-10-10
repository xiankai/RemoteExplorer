package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.db.FileType

private val GLYPH_INSET = 8.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerItem(
    name: String,
    type: FileType,
    thumbnails: List<String>,
    handleItemClick: () -> Unit,
    handleLongItemClick: () -> Unit,
    getImageUrl: GetImageUrl,
    ) {
    val inset = if (drawsPicture(type, thumbnails)) 0.dp else GLYPH_INSET
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = handleItemClick,
                onLongClick = handleLongItemClick,
            )
    ) {
        ExplorerItemIcon(
            getImageUrl,
            name,
            type,
            thumbnails,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(inset)
        )
        Text(
            text = name,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}
