package com.example.greetingcard

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

data class Picture(
    val filename: String,
    val uri: String
)

@Composable
fun Gallery(pictures: List<Picture>) {
    LazyVerticalGrid (
        columns = GridCells.Adaptive(minSize = 128.dp)
    ) {
        items(items = pictures, key = { picture -> picture.filename }) { picture ->
            GalleryItem(picture)
        }
    }
}

@Composable
fun GalleryItem(picture: Picture) {
    GalleryThumbnail(picture.uri, picture.filename)
}

@Composable
fun GalleryThumbnail(uri: String, filename: String) {
    AsyncImage(
        model = ImageRequest.Builder(context = LocalContext.current)
            .data(uri)
            .crossfade(true)
            .build(),
//        error = painterResource(R.drawable.ic_broken_image),
//        placeholder = painterResource(R.drawable.loading_img),
        contentDescription = filename,
        contentScale = ContentScale.Crop
    )

}