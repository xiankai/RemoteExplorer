package com.example.greetingcard.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
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

class PicturePreviewProvider: PreviewParameterProvider<Picture> {
    override val values = sequenceOf(
        Picture("5.2 Banners", "https://i.imgur.com/JER4BcR.jpeg")
    )
}

@Preview
@Composable
fun GalleryItem(
    @PreviewParameter(PicturePreviewProvider::class) picture: Picture
) {
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