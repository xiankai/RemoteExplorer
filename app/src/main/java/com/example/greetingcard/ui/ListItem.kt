package com.example.greetingcard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.greetingcard.data.File

@Composable
fun ListItem(file: File, onItemClick: (File) -> Unit) {
    Card(
        modifier = Modifier
            .size(128.dp)
            .clickable { onItemClick(file) }
        ,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Icon(
            Icons.Default.Folder,
            "folder",
            Modifier
                .fillMaxSize()
                .padding(8.dp)
                .weight(1f)
        )
        Spacer(modifier = Modifier.width((8.dp)))
        Text(
            text = file.filename,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp)
        )
    }
}