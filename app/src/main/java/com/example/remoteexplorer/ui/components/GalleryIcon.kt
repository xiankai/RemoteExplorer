import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.example.remoteexplorer.ui.components.GetImageUrl
import com.example.remoteexplorer.ui.components.IconType
import com.example.remoteexplorer.ui.components.ImageIcon

@Composable
fun GalleryIcon(
    getImageUrl: GetImageUrl,
    thumbnailUrls: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
    ) {
        SystemIcon(
            IconType.FOLDER,
            Modifier
                .align(Alignment.Center)
                .fillMaxSize()
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(
                if (thumbnailUrls.size >= 2) { 2 } else { 1 }
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(0.5f)
                .offset(y = (LocalConfiguration.current.screenHeightDp * 0.05f).dp),
            userScrollEnabled = false
        ) {
            items(4) { index ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    val filename = thumbnailUrls.getOrNull(index)
                    if (filename != null) {
                        ImageIcon(
                            getImageUrl, thumbnailUrls[index],
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                    }
                }
            }
        }
    }

}

class GalleryPreviewParameterProvider : PreviewParameterProvider<List<String>> {
    override val values = sequenceOf(
        listOf("a"),
        listOf("a", "b"),
        listOf("a", "b", "c"),
        listOf("a", "b", "c", "d"),
    )
}

@Preview(widthDp = 32, heightDp = 32)
@Preview(widthDp = 64, heightDp = 64)
@Preview(widthDp = 128, heightDp = 128)
@Preview(widthDp = 256, heightDp = 256)
@Composable
fun PreviewGalleryIcon(
    @PreviewParameter(GalleryPreviewParameterProvider::class) thumbnailUrls: List<String>
) {
    GalleryIcon(
        getImageUrl = { it },
        thumbnailUrls = thumbnailUrls
    )
}