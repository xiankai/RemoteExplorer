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
import com.example.remoteexplorer.data.db.FileType
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import androidx.compose.material3.Text
import com.example.remoteexplorer.ui.components.ExplorerItemIcon

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListViewItem(
    explorerViewModel: ExplorerViewModel,
    name: String,
    type: FileType,
    thumbnails: List<String>,
    handleItemClick: () -> Unit,
    handleLongItemClick: () -> Unit,
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
                explorerViewModel,
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