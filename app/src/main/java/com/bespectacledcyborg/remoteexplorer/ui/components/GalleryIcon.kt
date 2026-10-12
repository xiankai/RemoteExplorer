import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
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
    cover: CoverMode = CoverMode.DEFAULT,
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
            coverModeGlyph(cover)?.let { glyph ->
                CoverModeBadge(
                    glyph,
                    cover.label,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(side * 0.28f)
                )
            }
        }
    }
}

private val BADGE_GLYPH = Color(0xFF3B2F00)

private fun coverModeGlyph(cover: CoverMode): ImageVector? = when (cover) {
    CoverMode.DEFAULT -> null
    CoverMode.NEWEST -> Icons.Filled.History
    CoverMode.SHUFFLE -> Icons.Filled.RepeatOne
    CoverMode.SHUFFLE_EVERY_VISIT -> Icons.Filled.Repeat
}

@Composable
private fun CoverModeBadge(glyph: ImageVector, description: String, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        SystemIcon(IconType.FOLDER, Modifier.fillMaxSize())
        Icon(
            glyph,
            contentDescription = description,
            tint = BADGE_GLYPH,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = maxHeight * 0.12f)
                .size(maxHeight * 0.46f),
        )
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

class GalleryPreviewParameterProvider : PreviewParameterProvider<Pair<List<String>, CoverMode>> {
    override val values = sequenceOf(
        listOf("a") to CoverMode.DEFAULT,
        listOf("a", "b") to CoverMode.NEWEST,
        listOf("a", "b", "c") to CoverMode.SHUFFLE,
        listOf("a", "b", "c", "d") to CoverMode.SHUFFLE_EVERY_VISIT,
    )
}

@Preview(widthDp = 32, heightDp = 32)
@Preview(widthDp = 64, heightDp = 64)
@Preview(widthDp = 128, heightDp = 128)
@Preview(widthDp = 256, heightDp = 256)
@Composable
fun PreviewGalleryIcon(
    @PreviewParameter(GalleryPreviewParameterProvider::class) gallery: Pair<List<String>, CoverMode>
) {
    GalleryIcon(
        getImageUrl = ({ url, _ -> url }),
        thumbnailUrls = gallery.first,
        cover = gallery.second,
    )
}