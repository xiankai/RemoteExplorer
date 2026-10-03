import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.min
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.bespectacledcyborg.remoteexplorer.ui.components.GetImageUrl
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.ui.components.ImageIcon

@Composable
fun GalleryIcon(
    getImageUrl: GetImageUrl,
    thumbnailUrls: List<String>,
    modifier: Modifier = Modifier,
) {
    // The covers fill the tile and the folder glyph is only a corner badge (#72). The glyph
    // used to fill the whole cell with the covers squeezed to half of it underneath, so a
    // folder of photos read as a big yellow folder with small pictures in it. The badge
    // stays rather than going entirely: a folder with one cover would otherwise look
    // exactly like an image file.
    BoxWithConstraints(modifier = modifier) {
        // A square, so a 2x2 collage keeps square covers whatever shape the tile's icon
        // area is (the name below it makes that area wider than it is tall).
        val side = min(maxWidth, maxHeight)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(side)
                // The lazy grid clipped a second row that did not fit a short tile.
                .clipToBounds(),
        ) {
            // Plain rows rather than a LazyVerticalGrid. Four cells never needed laziness,
            // and a lazy layout inside every tile of a lazy grid is a subcomposition per
            // tile — the work that makes a fast scroll drop frames and leave tiles blank
            // behind it.
            val columns = if (thumbnailUrls.size >= 2) 2 else 1
            Column(Modifier.fillMaxSize()) {
                thumbnailUrls.take(4).chunked(columns).take(if (columns == 1) 1 else 2)
                    .forEach { row ->
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
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            // A lone image on the second row keeps its column's width.
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
            }

            SystemIcon(
                IconType.FOLDER,
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(side * 0.28f)
            )
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