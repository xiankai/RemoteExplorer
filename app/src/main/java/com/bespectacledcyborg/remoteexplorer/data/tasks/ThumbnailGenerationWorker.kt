package com.bespectacledcyborg.remoteexplorer.data.tasks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bespectacledcyborg.remoteexplorer.data.FileRepository
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import androidx.core.graphics.scale

import com.bespectacledcyborg.remoteexplorer.data.VideoDecoder
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import java.io.File
import java.io.InputStream

const val THUMBNAIL_SIZE = 128
const val THUMBNAIL_GENERATION_WORK_TAG = "generateThumbnail"
const val THUMBNAIL_WORKER_INPUT_CONNECTION_ID = "CONNECTION_ID"
const val THUMBNAIL_WORKER_INPUT_PARENT_PATH = "PARENT_PATH"
const val THUMBNAIL_WORKER_INPUT_FULL_PATH = "FULL_PATH"
const val THUMBNAIL_WORKER_OUTPUT_PARENT_PATH = "PARENT_PATH"
const val THUMBNAIL_WORKER_OUTPUT_FULL_PATH = "FULL_PATH"

class ThumbnailGenerationWorker(
    appContext: Context,
    params: WorkerParameters,
    private val fileRepository: FileRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val connectionId = inputData.getInt(THUMBNAIL_WORKER_INPUT_CONNECTION_ID, -1)
            val parentPath = inputData.getString(THUMBNAIL_WORKER_INPUT_PARENT_PATH)
                ?: return@withContext Result.failure()
            val fullPath = inputData.getString(THUMBNAIL_WORKER_INPUT_FULL_PATH)
                ?: return@withContext Result.failure()
            try {
                if (connectionId == -1) {
                    return@withContext Result.failure()
                }

                // Get dependencies
                val thumbnailManager = ThumbnailManager(applicationContext)

                // Skip if thumbnail already exists
                if (thumbnailManager.hasThumbnail(fullPath)) {
                    return@withContext Result.success()
                }

                val thumbnailFile = thumbnailManager.getThumbnailPath(fullPath)

                // Open the original file
                when (Utils.guessIconType(fullPath)) {
                    IconType.IMAGE -> {
                        val file = fileRepository.openFileDirectly(connectionId, fullPath)
                        generateImageThumbnail(thumbnailFile, file)

                        fileRepository.updateFileThumbnailStatus(connectionId, parentPath, fullPath, ThumbnailStatus.SUCCESS)
                    }

                    IconType.VIDEO -> {
//                        val dataSource = fileRepository.getDataSource(connectionId, path)

//                        generateVideoThumbnail(thumbnailFile, dataSource)

                        val stream = fileRepository.openFileDirectly(connectionId, fullPath).inputStream
                        generateFfmpegVideoThumbnailViaLibraries(thumbnailFile, stream)

                        fileRepository.updateFileThumbnailStatus(connectionId, parentPath, fullPath, ThumbnailStatus.SUCCESS)
                    }

                    else -> {
                        fileRepository.updateFileThumbnailStatus(connectionId, parentPath, fullPath, ThumbnailStatus.UNSUPPORTED)
                    }
                }

                Result.success()
            } catch (e: Exception) {
                Log.e("ThumbnailWorker", "Error generating thumbnail", e)
                fileRepository.updateFileThumbnailStatus(connectionId, parentPath, fullPath, ThumbnailStatus.ERROR)
                Result.failure()
            }
        }

    private fun calculateSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun resizeBitmap(bitmap: Bitmap, thumbnailFile: File) {
        val scaledBitmap =
            if (bitmap.width != THUMBNAIL_SIZE || bitmap.height != THUMBNAIL_SIZE) {
                bitmap.scale(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
            } else {
                bitmap
            }

        // Save to cache
        FileOutputStream(thumbnailFile).use { out ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        if (bitmap != scaledBitmap) {
            bitmap.recycle()
        }
        scaledBitmap.recycle()
    }

    private fun generateImageThumbnail(thumbnailFile: File, file: com.hierynomus.smbj.share.File) {
        // Read the file into a bitmap
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        // Calculate sample size for 128x128
        val sampleSize =
            calculateSampleSize(options, THUMBNAIL_SIZE, THUMBNAIL_SIZE)

        val decodingOptions =
            BitmapFactory.Options().apply { inSampleSize = sampleSize }

        val bitmap =
            BitmapFactory.decodeStream(file.inputStream, null, decodingOptions)
                ?: throw IllegalStateException("Bitmap is null")

        // Scale to exact size if needed
        resizeBitmap(bitmap, thumbnailFile)
    }

    private fun generateVideoThumbnail(thumbnailFile: File, datasource: MediaDataSource) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(datasource)
        val bitmap = retriever.getFrameAtIndex(0) ?: throw IllegalStateException("Bitmap is null")

        resizeBitmap(bitmap, thumbnailFile)
    }

    private fun generateFfmpegVideoThumbnailViaLibraries(
        thumbnailFile: File,
        inputStream: InputStream
    ) {
        val decoder = VideoDecoder()
        val bufferSize = 4 * 1024 * 1024
        val maxTries = 10
        var nthTry = 1
        var bytes = inputStream.readNBytes(bufferSize)
        var bitmap: Bitmap? = null
        while (nthTry < maxTries) {
            bitmap = decoder.decodeVideoFrame(bytes)
            if (bitmap == null) {
                nthTry++
                bytes += inputStream.readNBytes(bufferSize)
            } else {
                break
            }
        }

        resizeBitmap(
            bitmap
                ?: throw java.lang.IllegalStateException("Failed to decode video frame after $maxTries"),
            thumbnailFile
        )
    }
}