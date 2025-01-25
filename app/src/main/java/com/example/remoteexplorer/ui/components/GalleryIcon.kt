import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.remoteexplorer.R
import kotlin.math.min

@Composable
fun GalleryIcon(
    modifier: Modifier = Modifier,
    thumbnailUrls: List<String>, // List of thumbnail image URLs
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
    ) {
        items(min(4, thumbnailUrls.size)) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
            ) {
                val fallback = R.drawable.folder_24px
                AsyncImage(
                    model = thumbnailUrls.getOrNull(index),
                    contentDescription = "Thumbnail $index",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = painterResource(fallback),
                    error = painterResource(fallback)
                )
            }
        }
    }
}
