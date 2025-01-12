import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.remoteexplorer.R

@Composable
fun GalleryIcon(
    modifier: Modifier = Modifier,
    folderColor: Color = Color(0xFFFFD700), // Gold-like color for the folder
    thumbnailUrls: List<String>, // List of thumbnail image URLs
    gridSize: Dp = 16.dp // Size of individual grid thumbnails
) {
    println("this is a gallery: $thumbnailUrls")

    Box(
        modifier = modifier
            .size(64.dp)
            .background(folderColor, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        // Draw the 2x2 grid inside the folder
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp)
        ) {
            for (row in 0 until 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (col in 0 until 2) {
                        val index = row * 2 + col
                        Box(
                            modifier = Modifier
                                .size(gridSize)
                                .background(Color.Gray, shape = RoundedCornerShape(2.dp))
                        ) {
                            if (index < thumbnailUrls.size) {
                                val fallback = R.drawable.folder_24px
                                AsyncImage(
                                    model = thumbnailUrls[index],
                                    contentDescription = "Thumbnail $index",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                                    placeholder = painterResource(fallback),
                                    error = painterResource(fallback)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
