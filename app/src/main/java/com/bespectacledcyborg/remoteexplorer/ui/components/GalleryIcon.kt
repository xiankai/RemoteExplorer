import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.bespectacledcyborg.remoteexplorer.ui.components.GetImageUrl
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.ui.components.ImageIcon

private val SINGLE_COVER_BELOW = 120.dp

@Composable
fun GalleryIcon(
    getImageUrl: GetImageUrl,
    thumbnailUrls: List<String>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val side = min(maxWidth, maxHeight)
        val covers = thumbnailUrls.take(if (side < SINGLE_COVER_BELOW) 1 else 4)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(side)
                .clipToBounds(),
        ) {
            CoverCollage(getImageUrl, covers)
            SystemIcon(
                IconType.FOLDER,
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(side * 0.28f)
            )
        }
    }
}

@Composable
private fun CoverCollage(getImageUrl: GetImageUrl, covers: List<String>) {
    val columns = if (covers.size >= 2) 2 else 1
    Column(Modifier.fillMaxSize()) {
        covers.chunked(columns).take(columns).forEach { row ->
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                row.forEach { path ->
                    ImageIcon(
                        getImageUrl,
                        path,
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
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
        getImageUrl = ({ url, _ -> url }),
        thumbnailUrls = thumbnailUrls
    )
}