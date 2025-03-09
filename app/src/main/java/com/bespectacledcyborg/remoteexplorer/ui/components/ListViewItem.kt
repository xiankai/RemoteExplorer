import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import androidx.compose.material3.Text
import com.bespectacledcyborg.remoteexplorer.ui.components.ExplorerItemIcon
import com.bespectacledcyborg.remoteexplorer.ui.components.GetImageUrl

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListViewItem(
    name: String,
    type: FileType,
    thumbnails: List<String>,
    handleItemClick: () -> Unit,
    handleLongItemClick: () -> Unit,
    getImageUrl: GetImageUrl,
) {
    ListItem(
        modifier = Modifier
            .height(128.dp)
            .combinedClickable(
                onClick = handleItemClick,
                onLongClick = handleLongItemClick,
            ),
        headlineContent = { Text(name) },
        leadingContent = {
            ExplorerItemIcon(
                getImageUrl,
                name,
                type,
                thumbnails,
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .padding(8.dp)
            )
        }
    )
}