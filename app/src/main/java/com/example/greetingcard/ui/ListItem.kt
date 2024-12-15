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
import com.example.greetingcard.data.File
import java.net.URLConnection

@Composable
fun ListItem(file: File, handleChangePath: (File) -> Unit, handleOpenItem: (File) -> Unit) {
    Card(
        modifier = Modifier
            .size(128.dp)
            .clickable {
                val mimeType = URLConnection.guessContentTypeFromName(file.filename) ?: "folder"
                if (mimeType == "folder") {
                    handleChangePath(file)
                } else {
                    handleOpenItem(file)
                }
            }
        ,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        ListItemIcon(
            file.filename,
            Modifier
                .fillMaxSize()
                .padding(8.dp)
                .weight(1f)
        )
        Spacer(modifier = Modifier.width((8.dp)))
        Text(
            text = file.filename,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}