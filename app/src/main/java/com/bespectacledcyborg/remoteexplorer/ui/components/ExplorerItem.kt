package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.data.db.FileType

private val GLYPH_INSET = 8.dp
private const val DIMMED_ALPHA = 0.4f

@Composable
private fun CaptionBadge(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.ClosedCaption,
        contentDescription = "Has captions",
        tint = Color.White,
        modifier = modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(2.dp)
            .size(18.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerItem(
    name: String,
    type: FileType,
    thumbnails: List<String>,
    handleItemClick: () -> Unit,
    handleLongItemClick: () -> Unit,
    getImageUrl: GetImageUrl,
    captioned: Boolean = false,
    cover: CoverMode = CoverMode.DEFAULT,
    dimmed: Boolean = false,
    ) {
    val inset = if (drawsPicture(type, thumbnails)) 0.dp else GLYPH_INSET
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) DIMMED_ALPHA else 1f)
            .combinedClickable(
                onClick = handleItemClick,
                onLongClick = handleLongItemClick,
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            ExplorerItemIcon(
                getImageUrl,
                name,
                type,
                thumbnails,
                cover,
                Modifier
                    .fillMaxSize()
                    .padding(inset)
            )
            if (captioned) CaptionBadge(Modifier.align(Alignment.TopEnd))
        }
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

@Composable
fun ExplorerItemPlaceholder() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Text(
            text = "",
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            maxLines = 1,
        )
    }
}
