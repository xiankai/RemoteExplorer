import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.remoteexplorer.ui.components.ImageIcon
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@Composable
fun GalleryIcon(
    explorerViewModel: ExplorerViewModel,
    thumbnailUrls: List<String>,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
    ) {
        items(4) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFFFFD700), shape = RoundedCornerShape(4.dp)),
            ) {
                val filename = thumbnailUrls.getOrNull(index)
                if (filename != null) {
                    AsyncImage(
                        model = ImageIcon(explorerViewModel, thumbnailUrls[index], modifier),
                        contentDescription = filename,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}