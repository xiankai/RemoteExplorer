import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.remoteexplorer.ui.components.IconType

@Composable
fun SystemIcon(iconType: IconType, modifier: Modifier) {
    val icon = when(iconType) {
        IconType.CONNECTION-> Icons.Default.FolderShared
        IconType.ADD_CONNECTION -> Icons.Default.CreateNewFolder
        IconType.IMAGE -> Icons.Default.Image
        IconType.VIDEO -> Icons.Default.VideoFile
        IconType.AUDIO -> Icons.Default.AudioFile
        IconType.TEXT -> Icons.Default.Description
        else -> Icons.AutoMirrored.Default.InsertDriveFile
    }

    androidx.compose.material3.Icon(
        icon,
        contentDescription = iconType.name,
        modifier = modifier
    )
}