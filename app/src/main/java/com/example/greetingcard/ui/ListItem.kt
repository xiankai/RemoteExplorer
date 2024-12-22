package com.example.greetingcard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import java.net.URLConnection

@Composable
fun ListItem(
    filename: String,
    url: String,
    handleChangePath: (String) -> Unit,
    handleOpenItem: (String) -> Unit) {
    Card(
        modifier = Modifier
            .size(128.dp)
            .clickable {
                val mimeType = URLConnection.guessContentTypeFromName(filename) ?: "folder"
                if (mimeType == "folder") {
                    handleChangePath(filename)
                } else {
                    handleOpenItem(filename)
                }
            }
        ,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        ListItemIcon(
            filename,
            url,
            Modifier
                .fillMaxSize()
                .padding(8.dp)
                .weight(1f),
            DisplayType.MANGA
        )
        Spacer(modifier = Modifier.width((8.dp)))
        Text(
            text = filename,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}