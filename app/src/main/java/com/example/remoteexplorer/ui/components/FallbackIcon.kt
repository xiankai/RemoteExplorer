import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.example.remoteexplorer.R
import com.example.remoteexplorer.ui.components.IconType
import com.example.remoteexplorer.utils.Utils


@Composable
fun FallbackIcon(filename: String?, modifier: Modifier) {
    var fallback = R.drawable.folder_24px
    if (filename != null) {
        val iconType = Utils.guessIconType(filename)
        fallback = when (iconType) {
            IconType.IMAGE -> R.drawable.image_24px
            IconType.VIDEO -> R.drawable.movie_24px
            IconType.AUDIO -> R.drawable.music_note_24px
            IconType.TEXT -> R.drawable.description_24px
            else -> R.drawable.draft_24px
        }
    }

    return Image(
        painter = painterResource(fallback),
        contentDescription = filename,
        modifier = modifier,
    )
}