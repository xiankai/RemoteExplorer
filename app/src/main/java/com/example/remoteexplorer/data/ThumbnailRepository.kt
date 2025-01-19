package com.example.remoteexplorer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.min

class ThumbnailRepository (
    private val context: Context,
    private val sambaFileDataSource: SambaFileDataSource,
    private val dispatcher: CoroutineDispatcher
) {
    suspend fun generateImageThumbnail(path: String) {
        return withContext(dispatcher) {
            val file = sambaFileDataSource.openFile(path)
            val thumbnail = createThumbnailFromStream(file.inputStream, Size(480, 640))
            val thumbnailFile = File(context.filesDir, path)
            FileOutputStream(thumbnailFile).use { out ->
                thumbnail?.compress(Bitmap.CompressFormat.PNG, 100, out)
                Log.d("ThumbnailRepository", "thumbnail saved to ${thumbnailFile.absolutePath}")
            }
        }
    }

    // Adapted from https://developer.android.com/social-and-messaging/guides/media-thumbnails
    // Where the argument is InputStream instead of Uri
    private fun createThumbnailFromStream(inputStream: InputStream, size: Size): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        if ( options.outHeight != 0 ) {
            // we've got bounds
            val widthSample = options.outWidth / size.width
            val heightSample = options.outHeight / size.height
            val sample = min(widthSample, heightSample)
            if (sample > 1) {
                options.inSampleSize = sample
            }
        }

        val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
        return bitmap
    }

    fun fetchImageThumbnail(path: String): ImageBitmap? {
        val filesDir = context.filesDir
        return try {
            val file = File(filesDir, path)
            val bitmap = BitmapFactory.decodeFile(file.absolutePath).asImageBitmap()
            bitmap
        } catch (e: NullPointerException) {
            null
        }
    }
}