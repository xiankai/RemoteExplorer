package com.example.greetingcard.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers

@Composable
fun List(
    listViewModel: ListViewModel = ListViewModel(Dispatchers.IO)
) {
    val listUiState by listViewModel.uiState.collectAsState()
    val context = LocalContext.current


    Column(
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            color = Color.White,
            text = listUiState.currentPath,
            style = typography.titleLarge,
        )

        Button(onClick = {
            Toast.makeText(context, "Connecting", Toast.LENGTH_SHORT).show()
            listViewModel.connect()
        }) {
            Text(
                text = "Connect"
            )
        }

        Button(onClick = {
            Toast.makeText(context, "Querying", Toast.LENGTH_SHORT).show()
            listViewModel.listFiles()
        }) {
            Text(
                text = "Fetch"
            )
        }

        Text(
            color = Color.Black,
            text = listUiState.files.size.toString(),
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 128.dp),
        ) {
            items(listUiState.files) { file ->
                ListItem(file) {
                    clickedItem -> listViewModel.listFiles(clickedItem.filename)
                }
            }
        }
    }
}