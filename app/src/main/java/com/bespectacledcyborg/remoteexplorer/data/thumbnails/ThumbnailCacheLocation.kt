package com.bespectacledcyborg.remoteexplorer.data.thumbnails

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import java.io.File

private const val TAG = "ThumbnailCacheLocation"

/** The directory name used on every volume, so a cache is recognisable wherever it landed. */
private const val CACHE_DIR_NAME = "thumbnails"

/**
 * How much the thumbnails may occupy on internal storage. At the 128 px they are written
 * out as, that is room for tens of thousands of them — far more than a browsing session
 * touches, while still being a bound rather than no bound at all.
 */
const val MAX_INTERNAL_THUMBNAIL_CACHE_BYTES = 256L * 1024 * 1024

/**
 * The same bound on a card, which is the point of putting it there.
 *
 * A separate number rather than one shared setting: 256 MB was chosen against internal
 * storage, where space is scarce and shared with everything else on the device. A card the
 * user deliberately pointed the cache at has neither constraint, and a library big enough
 * to want a card is big enough to blow through 256 MB of thumbnails.
 */
const val MAX_EXTERNAL_THUMBNAIL_CACHE_BYTES = 4L * 1024 * 1024 * 1024

/**
 * A place the thumbnail cache can live.
 *
 * [id] is what gets stored in settings: null means internal storage, and anything else is a
 * volume UUID from [StorageManager]. The UUID rather than the path, because the path a card
 * is mounted at is not stable across reboots or across devices, and a stored path that no
 * longer exists is indistinguishable from a card that was removed.
 */
data class ThumbnailVolume(
    val id: String?,
    val label: String,
    val directory: File,
    val freeBytes: Long,
) {
    val isInternal: Boolean get() = id == null
}

/**
 * Every volume the cache could be put on, internal first.
 *
 * `getExternalFilesDirs` returns one app-specific directory per volume. They need no runtime
 * permission and are removed when the app is uninstalled, which is the right lifecycle for a
 * cache — so this avoids `MANAGE_EXTERNAL_STORAGE` and the Storage Access Framework
 * entirely. Entries can be null for a volume that is currently unavailable, hence the
 * filtering.
 */
fun availableThumbnailVolumes(context: Context): List<ThumbnailVolume> {
    val internal = internalThumbnailCache(context)
    val volumes = mutableListOf(
        ThumbnailVolume(
            id = null,
            label = "Internal storage",
            directory = internal,
            freeBytes = internal.parentFile?.usableSpace ?: 0L,
        )
    )

    val storageManager = context.getSystemService(StorageManager::class.java)
    for (dir in context.getExternalFilesDirs(null)) {
        if (dir == null) continue
        if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) continue

        // A null uuid is the primary (emulated) volume, which is the same physical storage
        // as internal — offering it would be two names for one place.
        val uuid = runCatching { storageManager?.getStorageVolume(dir)?.uuid }.getOrNull()
            ?: continue

        volumes += ThumbnailVolume(
            id = uuid,
            label = "SD card ($uuid)",
            directory = File(dir, CACHE_DIR_NAME),
            freeBytes = dir.usableSpace,
        )
    }

    return volumes
}

/**
 * Where the cache should actually be written, given the stored setting.
 *
 * Falls back to internal storage whenever the named volume is missing or unmounted, which
 * is the normal case rather than an error: a card can be ejected between two scrolls. The
 * caller sees an empty cache and re-renders, because a thumbnail that cannot be read is a
 * cache miss and nothing worse.
 */
fun resolveThumbnailCacheDir(context: Context, volumeId: String?): File {
    if (volumeId == null) return internalThumbnailCache(context).apply { mkdirs() }

    val volume = availableThumbnailVolumes(context).firstOrNull { it.id == volumeId }
    if (volume == null) {
        Log.i(TAG, "Volume $volumeId is not available, using internal storage")
        return internalThumbnailCache(context).apply { mkdirs() }
    }

    if (!volume.directory.mkdirs() && !volume.directory.isDirectory) {
        Log.w(TAG, "Cannot use ${volume.directory}, using internal storage")
        return internalThumbnailCache(context).apply { mkdirs() }
    }

    return volume.directory
}

/**
 * Deletes the cache on [volumeId], for when the setting has just moved off it.
 *
 * Deleted rather than moved. Thumbnails being regenerable is the whole reason they are the
 * safe thing to put on removable storage, so copying up to 4 GB of them between volumes —
 * slowly, and with a half-finished state to reason about if it is interrupted — buys back
 * only the seconds it takes to re-render the folder currently on screen. What is not
 * acceptable is leaving them behind: a cache nothing points at is not a cache, it is litter
 * on the user's card.
 *
 * A volume that has already been removed cannot be cleaned up here. That is survivable
 * without a retry queue, because these are app-scoped directories that Android deletes with
 * the app.
 */
fun discardThumbnailCache(context: Context, volumeId: String?) {
    val directory = if (volumeId == null) {
        internalThumbnailCache(context)
    } else {
        availableThumbnailVolumes(context).firstOrNull { it.id == volumeId }?.directory
    }

    if (directory == null) {
        Log.i(TAG, "Volume $volumeId is gone; leaving its cache to uninstall")
        return
    }

    if (directory.exists() && !directory.deleteRecursively()) {
        Log.w(TAG, "Could not fully delete $directory")
    }
}

/** The ceiling that applies to a cache in [directory]. */
fun maxThumbnailCacheBytes(context: Context, directory: File): Long =
    if (directory == internalThumbnailCache(context)) {
        MAX_INTERNAL_THUMBNAIL_CACHE_BYTES
    } else {
        MAX_EXTERNAL_THUMBNAIL_CACHE_BYTES
    }

private fun internalThumbnailCache(context: Context) = File(context.cacheDir, CACHE_DIR_NAME)
