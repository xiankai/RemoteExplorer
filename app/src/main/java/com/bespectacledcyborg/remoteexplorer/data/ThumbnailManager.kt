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
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.maxThumbnailCacheBytes
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.resolveThumbnailCacheDir
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.deleteOtherSizes
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.thumbnailCacheKey
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ThumbnailsMap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Marks a render in progress, so a trim running alongside it leaves it alone. */
private const val SCRATCH_SUFFIX = ".tmp"

class ThumbnailManager(
    val context: Context,
    /**
     * Which volume to write to, as a UUID or null for internal storage.
     *
     * Asked each time rather than resolved once at construction, because the answer moves
     * underneath us in two ways: the user can repoint the cache in settings, and a card can
     * be ejected at any moment. Three ThumbnailManagers exist in the process, so resolving
     * at construction would also let whichever was built first decide for all of them.
     *
     * It is a single indexed read of a one-row table, next to an SMB read and a video
     * decode, so it is asked per use rather than mirrored into something that could go
     * stale.
     */
    private val volumeId: suspend () -> String?,
) {
    private val pendingThumbnails = ConcurrentHashMap<String, Boolean>()
    private val workManager by lazy { WorkManager.getInstance(context) }

    // Resolving touches the filesystem — a mount-state check and an mkdirs — so the
    // directory is kept rather than recomputed from scratch per thumbnail.
    @Volatile
    private var resolved: Pair<String?, File>? = null

    private suspend fun thumbnailCache(): File {
        val id = volumeId()
        // Both halves matter. The id catches the user repointing the cache; isDirectory
        // catches the card being taken out, which the id cannot see because the setting
        // still names the volume that has gone. Without it the stale path is handed back
        // forever and createTempFile throws on it — a 500 on every thumbnail rather than
        // the fallback to internal storage that resolveThumbnailCacheDir is there to do.
        resolved?.let { (cachedId, dir) -> if (cachedId == id && dir.isDirectory) return dir }
        val dir = resolveThumbnailCacheDir(context, id)
        resolved = id to dir
        return dir
    }

    suspend fun getThumbnailPath(connectionId: Int, originalPath: String): File =
        File(thumbnailCache(), thumbnailCacheKey(connectionId, originalPath))

    suspend fun hasThumbnail(connectionId: Int, originalPath: String): Boolean =
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
        val cache = thumbnailCache()
        val destination = File(cache, thumbnailCacheKey(connectionId, path))
        // .tmp rather than .jpg so a concurrent trim cannot delete a render in flight. In
        // the same directory as the destination, so publishing it is a rename rather than
        // a copy — which also means it has to follow the cache onto whichever volume that
        // is, since a rename across volumes is not one.
        val scratch = File.createTempFile("render", SCRATCH_SUFFIX, cache)

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

        trimToSize(cache)
        return destination
    }

    /**
     * Renders to a throwaway file, never touching the cache.
     *
     * For the `?decoder=` comparison in #41: two implementations rendering the same file
     * would otherwise share one cache key, so the second would be served the first one's
     * output and measured at zero. Bypassing the cache also makes every diagnostic request
     * a cold render, which is the only kind worth timing.
     *
     * The caller gets a file it owns and must delete.
     */
    suspend fun renderUncached(render: suspend (File) -> Boolean): File? {
        val scratch = File.createTempFile("probe", SCRATCH_SUFFIX, thumbnailCache())
        val rendered = try {
            render(scratch)
        } catch (e: Exception) {
            scratch.delete()
            throw e
        }
        if (!rendered || scratch.length() == 0L) {
            scratch.delete()
            return null
        }
        return scratch
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
     *
     * The ceiling depends on where [cache] is: internal storage is scarce and shared with
     * the rest of the device, a card the user deliberately chose is neither.
     */
    fun trimToSize(
        cache: File,
        maxBytes: Long = maxThumbnailCacheBytes(context, cache),
    ) {
        val files = cache
            .listFiles { file -> !file.name.endsWith(SCRATCH_SUFFIX) }
            ?.let(::deleteOtherSizes)
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

    private suspend fun queueThumbnailGeneration(connectionId: Int, parentPath: String, fullPath: String): Boolean {
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
    suspend fun queueBatchThumbnailGeneration(connectionId: Int, files: ThumbnailsMap): ThumbnailsMap {
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
