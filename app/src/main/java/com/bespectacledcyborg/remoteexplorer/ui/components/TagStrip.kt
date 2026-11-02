package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.TagChip

@Composable
fun TagStrip(
    tags: List<TagChip>,
    onOpenTag: (TagChip) -> Unit,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    if (tags.isEmpty()) return
    val content = if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Sell,
            contentDescription = "Tags",
            tint = content,
            modifier = Modifier.padding(end = 4.dp).size(18.dp),
        )
        tags.forEach { tag ->
            AssistChip(
                onClick = { onOpenTag(tag) },
                label = { Text(tag.label, maxLines = 1) },
                colors = AssistChipDefaults.assistChipColors(labelColor = content),
                border = BorderStroke(1.dp, content.copy(alpha = 0.5f)),
            )
        }
    }
}
