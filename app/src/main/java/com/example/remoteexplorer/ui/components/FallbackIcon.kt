import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import com.example.remoteexplorer.R
import com.example.remoteexplorer.ui.components.IconType
import com.example.remoteexplorer.utils.Utils


@Composable
fun FallbackIcon(filename: String?, modifier: Modifier) {
    if (filename == null) {
        return AsyncImage(
            model = R.drawable.folder_24px,
            contentDescription = "null",
            modifier = modifier,
            placeholder = painterResource(R.drawable.folder_24px),
            error = painterResource(R.drawable.folder_24px)
        )
    }

    val iconType = Utils.guessIconType(filename)

    val fallback = when (iconType) {
        IconType.IMAGE -> R.drawable.image_24px
        IconType.VIDEO -> R.drawable.movie_24px
        IconType.AUDIO -> R.drawable.music_note_24px
        IconType.TEXT -> R.drawable.description_24px
        else -> R.drawable.draft_24px
    }

    return AsyncImage(
        model = fallback,
        contentDescription = filename,
        modifier = modifier,
        placeholder = painterResource(fallback),
        error = painterResource(fallback)
    )
}