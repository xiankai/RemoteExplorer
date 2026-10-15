import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType

@Composable
fun SystemIcon(iconType: IconType, modifier: Modifier = Modifier) {
    // Tinted to the theme. Untinted, these vectors are black, which is what drew the
    // connection tiles black on the dark background (#58).
    var colorFilter: ColorFilter? = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
    val icon = when(iconType) {
        IconType.CONNECTION-> Icons.Default.FolderShared
        IconType.ADD_CONNECTION -> Icons.Default.CreateNewFolder
        IconType.FOLDER -> {
            colorFilter = ColorFilter.tint(Color(0xFFFFD700))
            Icons.Default.Folder
        }
        IconType.IMAGE -> Icons.Default.Image
        IconType.VIDEO -> Icons.Default.VideoFile
        IconType.AUDIO -> Icons.Default.AudioFile
        IconType.TEXT -> Icons.Default.Description
        else -> Icons.AutoMirrored.Default.InsertDriveFile
    }

    Image(
        icon,
        contentDescription = iconType.name,
        colorFilter = colorFilter,
        modifier = modifier
    )
}