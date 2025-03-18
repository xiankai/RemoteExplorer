import android.media.MediaDataSource
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel

class VideoDataSource(
    private val explorerViewModel: ExplorerViewModel,
    private val connectionId: Int,
    private val fullPath: String,
    private val closeHandler: () -> Unit,
): MediaDataSource() {
    override fun close() {
        closeHandler()
    }

    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (buffer == null) {
            return -1
        }
        return explorerViewModel.openFileRange(connectionId, fullPath, buffer, position.toInt(), size)
    }

    override fun getSize(): Long {
        return explorerViewModel.getFileSize(connectionId, fullPath)
    }
}