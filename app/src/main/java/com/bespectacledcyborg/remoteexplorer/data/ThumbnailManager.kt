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
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.thumbnailCacheKey
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ThumbnailsMap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How much of the app's cache directory the thumbnails may occupy. At the 128 px they are
 * written out as, that is room for tens of thousands of them — far more than a browsing
 * session touches, while still being a bound rather than no bound at all.
 */
private const val MAX_THUMBNAIL_CACHE_BYTES = 256L * 1024 * 1024

/** Marks a render in progress, so a trim running alongside it leaves it alone. */
private const val SCRATCH_SUFFIX = ".tmp"

class ThumbnailManager(
    val context: Context,
) {
    private val thumbnailCache = File(context.cacheDir, "thumbnails")
    private val pendingThumbnails = ConcurrentHashMap<String, Boolean>()
    private val workManager = WorkManager.getInstance(context)

    init {
        thumbnailCache.mkdirs()
    }

    fun getThumbnailPath(connectionId: Int, originalPath: String): File =
        File(thumbnailCache, thumbnailCacheKey(connectionId, originalPath))

    fun hasThumbnail(connectionId: Int, originalPath: String): Boolean =
        getThumbnailPath(connectionId, originalPath).exists()

    /**
     * Renders through a scratch file and moves it into place only once [render] reports
     * success and has actually written something.
     *
     * Writing straight to the cache path would publish a half-written or empty file the
     * moment anything went wrong mid-render, and since presence is what marks a thumbnail
     * cached, that broken entry would be served forever. The scratch file lives in the same
     * directory so the move is a rename rather than a copy.
     */
    suspend fun renderInto(
        connectionId: Int,
        path: String,
        render: suspend (File) -> Boolean,
    ): File? {
        val destination = getThumbnailPath(connectionId, path)
        // .tmp rather than .jpg so a concurrent trim cannot delete a render in flight.
        val scratch = File.createTempFile("render", SCRATCH_SUFFIX, thumbnailCache)

        val rendered = try {
            render(scratch)
        } catch (e: Exception) {
            scratch.delete()
            throw e
        }

        if (!rendered || scratch.length() == 0L || !scratch.renameTo(destination)) {
            scratch.delete()
            return null
        }

        trimToSize()
        return destination
    }

    /**
     * Drops the least recently used thumbnails until the cache is back under its ceiling.
     *
     * Nothing bounded this directory before: "Max Storage Cache Size" in settings governs
     * Sketch's download cache, and these files are ours. Browsing a large library filled the
     * app's cache directory without limit, and Android reclaims that by deleting the lot at
     * the least convenient moment.
     *
     * Called after a write rather than on a schedule — the only thing that grows the cache
     * is writing to it.
     */
    fun trimToSize(maxBytes: Long = MAX_THUMBNAIL_CACHE_BYTES) {
        val files = thumbnailCache
            .listFiles { file -> !file.name.endsWith(SCRATCH_SUFFIX) }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        // lastModified stands in for last use: every entry is written once and then only
        // read, so age is the only signal available without tracking reads ourselves.
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun queueThumbnailGeneration(connectionId: Int, parentPath: String, fullPath: String): Boolean {
        val key = "$connectionId:$parentPath:$fullPath"
        // Skip if already queued or exists
        if (pendingThumbnails.putIfAbsent(key, true) != null ||
            hasThumbnail(connectionId, fullPath)
        ) {
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
