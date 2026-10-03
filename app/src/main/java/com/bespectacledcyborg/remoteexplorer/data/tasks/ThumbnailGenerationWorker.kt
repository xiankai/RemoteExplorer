package com.bespectacledcyborg.remoteexplorer.data.tasks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bespectacledcyborg.remoteexplorer.data.FileRepository
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import com.bespectacledcyborg.remoteexplorer.data.SourceFile
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.VideoDecoder
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.RemoteRandomAccess
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The long side, in pixels, every thumbnail is rendered at — and so what a grid cell shows.
 *
 * It was 128, which every cell upscaled roughly threefold (#73). 512 is about a cell's own
 * width at the default three columns: a phone's is around 360 px, a 1600 px portrait
 * tablet's around 530. It also names the cache files (see `thumbnailCacheKey`), so changing
 * it retires every thumbnail rendered at the old size instead of serving them on.
 */
const val THUMBNAIL_SIZE = 512
const val THUMBNAIL_GENERATION_WORK_TAG = "generateThumbnail"
const val THUMBNAIL_WORKER_INPUT_CONNECTION_ID = "CONNECTION_ID"
const val THUMBNAIL_WORKER_INPUT_PARENT_PATH = "PARENT_PATH"
const val THUMBNAIL_WORKER_INPUT_FULL_PATH = "FULL_PATH"
const val THUMBNAIL_WORKER_OUTPUT_PARENT_PATH = "PARENT_PATH"
const val THUMBNAIL_WORKER_OUTPUT_FULL_PATH = "FULL_PATH"

class ThumbnailGenerationWorker(
    appContext: Context,
    params: WorkerParameters,
    private val fileRepository: FileRepository,
    private val settingsRepository: SettingsRepository,
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
                val thumbnailManager = ThumbnailManager(applicationContext) {
                    settingsRepository.getSettingsOnce()?.thumbnailCacheVolume
                }

                // Skip if thumbnail already exists
                if (thumbnailManager.hasThumbnail(connectionId, fullPath)) {
                    return@withContext Result.success()
                }

                val thumbnailFile = thumbnailManager.getThumbnailPath(connectionId, fullPath)

                // Open the original file
                when (Utils.guessIconType(fullPath)) {
                    IconType.IMAGE -> {
                        val file = fileRepository.openFileDirectly(connectionId, fullPath)
                        generateImageThumbnail(thumbnailFile, file)

                        fileRepository.updateFileThumbnailStatus(
                            connectionId,
                            parentPath,
                            fullPath,
                            ThumbnailStatus.SUCCESS
                        )
                    }

                    IconType.VIDEO -> {
                        // The same seekable reader the thumbnail sources use. This worker
                        // is enqueued from a path that is currently switched off
                        // (ExplorerViewModel.thumbnailsJob), but it must not be the one
                        // copy of the old growing-prefix read left behind to be switched
                        // back on.
                        RemoteRandomAccess {
                            fileRepository.openFileDirectly(connectionId, fullPath)
                        }.use { reader ->
                            val frame = VideoDecoder().decodeVideoFrame(reader)
                                ?: throw IllegalStateException("No decodable frame in $fullPath")
                            resizeBitmap(frame, thumbnailFile)
                        }

                        fileRepository.updateFileThumbnailStatus(
                            connectionId,
                            parentPath,
                            fullPath,
                            ThumbnailStatus.SUCCESS
                        )
                    }

                    else -> {
                        fileRepository.updateFileThumbnailStatus(
                            connectionId,
                            parentPath,
                            fullPath,
                            ThumbnailStatus.UNSUPPORTED
                        )
                    }
                }

                Result.success()
            } catch (e: Exception) {
                Log.e("ThumbnailWorker", "Error generating thumbnail", e)
                fileRepository.updateFileThumbnailStatus(
                    connectionId,
                    parentPath,
                    fullPath,
                    ThumbnailStatus.ERROR
                )
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

    private fun generateImageThumbnail(thumbnailFile: File, file: SourceFile) {
        // Read the file into a bitmap
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        // Calculate sample size for THUMBNAIL_SIZE
        val sampleSize =
            calculateSampleSize(options, THUMBNAIL_SIZE, THUMBNAIL_SIZE)

        val decodingOptions =
            BitmapFactory.Options().apply { inSampleSize = sampleSize }

        val bitmap =
            BitmapFactory.decodeStream(file.inputStream(), null, decodingOptions)
                ?: throw IllegalStateException("Bitmap is null")

        // Scale to exact size if needed
        resizeBitmap(bitmap, thumbnailFile)
    }
}

/**
 * How much to scale a [width] x [height] bitmap so its long side is [THUMBNAIL_SIZE].
 *
 * Never above 1: an image already smaller than a thumbnail is stored as it is. Blowing it up
 * here would cost the disk of a full-size thumbnail and look no better than letting the cell
 * scale it.
 */
internal fun thumbnailScale(width: Int, height: Int): Float =
    minOf(1f, THUMBNAIL_SIZE.toFloat() / maxOf(width, height, 1))

fun resizeBitmap(bitmap: Bitmap, thumbnailFile: File) {
    // Fit inside the box rather than filling it. Scaling straight to THUMBNAIL_SIZE square
    // ignored the aspect ratio, so every thumbnail that was not already square came out
    // stretched — which is most photographs.
    val scale = thumbnailScale(bitmap.width, bitmap.height)
    val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

    val scaledBitmap =
        if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
            bitmap.scale(targetWidth, targetHeight)
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