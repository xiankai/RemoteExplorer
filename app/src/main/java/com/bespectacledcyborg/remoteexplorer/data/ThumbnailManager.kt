package com.bespectacledcyborg.remoteexplorer.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_GENERATION_WORK_TAG
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_WORKER_INPUT_CONNECTION_ID
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_WORKER_INPUT_FULL_PATH
import com.bespectacledcyborg.remoteexplorer.data.tasks.THUMBNAIL_WORKER_INPUT_PARENT_PATH
import com.bespectacledcyborg.remoteexplorer.data.tasks.ThumbnailGenerationWorker
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ThumbnailsMap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ThumbnailManager(
    val context: Context,
) {
    private val thumbnailCache = File(context.cacheDir, "thumbnails")
    private val pendingThumbnails = ConcurrentHashMap<String, Boolean>()
    private val workManager = WorkManager.getInstance(context)

    init {
        thumbnailCache.mkdirs()
    }

    fun getThumbnailPath(originalPath: String): File {
        val hash = originalPath.hashCode().toString() + ".jpg"
        return File(thumbnailCache, hash)
    }

    fun hasThumbnail(originalPath: String): Boolean {
        return getThumbnailPath(originalPath).exists()
    }

    private fun queueThumbnailGeneration(connectionId: Int, parentPath: String, fullPath: String): Boolean {
        val key = "$connectionId:$parentPath:$fullPath"
        // Skip if already queued or exists
        if (pendingThumbnails.putIfAbsent(key, true) != null || hasThumbnail(fullPath)) {
            return true
        }

        createThumbnailJob(connectionId, parentPath, fullPath)
        return false
    }

    private fun createThumbnailJob(connectionId: Int, parentPath: String, fullPath: String): Operation {
        val key = "$connectionId:$parentPath:$fullPath"

        // Create work request
        val workRequest =
            OneTimeWorkRequestBuilder<ThumbnailGenerationWorker>()
                .addTag(THUMBNAIL_GENERATION_WORK_TAG)
                .setInputData(
                    Data.Builder()
                        .putInt(THUMBNAIL_WORKER_INPUT_CONNECTION_ID, connectionId)
                        .putString(THUMBNAIL_WORKER_INPUT_PARENT_PATH, parentPath)
                        .putString(THUMBNAIL_WORKER_INPUT_FULL_PATH, fullPath)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

        return workManager.enqueueUniqueWork("generateThumbnail:$key", ExistingWorkPolicy.KEEP, workRequest)
    }

    // Queue multiple thumbnails at once (for background processing)
    fun queueBatchThumbnailGeneration(connectionId: Int, files: ThumbnailsMap): ThumbnailsMap {
        val successfulThumbnails = mutableMapOf<String, MutableList<String>>()

        files.forEach { (parentPath, thumbnails) ->
            val successfulFiles = thumbnails.filter { queueThumbnailGeneration(connectionId, parentPath, it) }
            if (successfulFiles.isNotEmpty()) {
                successfulThumbnails[parentPath] = successfulFiles.toMutableList()
            }
        }

        return successfulThumbnails
    }

}
