import android.media.MediaDataSource
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel

class VideoThumbnail(private val explorerViewModel: ExplorerViewModel, private val fullPath: String): MediaDataSource() {
    override fun close() {
        // TODO: Not yet implemented
    }

    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (buffer == null) {
            return -1
        }
        return explorerViewModel.openFileRange(fullPath, buffer, position.toInt(), size)
    }

    override fun getSize(): Long {
        return explorerViewModel.getFileSize(fullPath)
    }
}