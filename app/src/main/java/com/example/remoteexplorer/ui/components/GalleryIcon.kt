import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.remoteexplorer.ui.components.IconType
import com.example.remoteexplorer.ui.components.ImageIcon
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

@Composable
fun GalleryIcon(
    explorerViewModel: ExplorerViewModel,
    thumbnailUrls: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
    ) {
        SystemIcon(IconType.FOLDER,
            Modifier
                .align(Alignment.Center)
                .fillMaxSize())

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .align(Alignment.Center).fillMaxSize(0.5f).offset(y = 12.dp),
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
                        ImageIcon(explorerViewModel, thumbnailUrls[index])
                    }
                }
            }
        }
    }

}