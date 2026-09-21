package com.bespectacledcyborg.remoteexplorer.data.tags

import LocalDatabaseDataSource
import android.util.Log
import com.adobe.internal.xmp.XMPMetaFactory
import com.bespectacledcyborg.remoteexplorer.data.FileRepository
import com.bespectacledcyborg.remoteexplorer.data.db.FileType
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile

/** Dublin Core, the namespace nearly every tagging tool writes keywords into. */
private const val DUBLIN_CORE_NAMESPACE = "http://purl.org/dc/elements/1.1/"
private const val SUBJECT_PROPERTY = "subject"

/**
 * Reads tags from `.xmp` sidecars sitting next to the media, one SMB round trip per sidecar.
 *
 * Portable and slow, and the fallback that always works: the share describes itself, so any
 * device can rebuild the index from the files alone.
 */
class XmpSidecarTagSource(
    private val fileRepository: FileRepository,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
) : TagSource {

    override val capabilities = TagSourceCapabilities(
        // A sidecar only becomes a row when its directory is listed, so the index reaches
        // exactly as far as the user has browsed.
        indexesUnvisitedDirectories = false,
        supportsWrites = false,
    )

    override suspend fun index(connectionId: Int, reindexAll: Boolean, limit: Int): Int {
        val tagFiles = localDatabaseDataSource.getTagFiles(connectionId, reindexAll, limit)
        if (tagFiles.isEmpty()) return 0

        Log.d(TAG, "Scanning ${tagFiles.size} sidecars")

        // One directory read per directory in the batch rather than one per sidecar: pairing
        // used to run a LIKE query for every single file, so a folder of a thousand photos
        // re-queried it a thousand times.
        val siblingsByDirectory = tagFiles.map { it.parentPath }.distinct().associateWith {
            localDatabaseDataSource.getFiles(connectionId, it, filter = "")
                .filter { file -> file.type != FileType.TAG }
        }

        tagFiles.forEach { tagFile ->
            val siblings = siblingsByDirectory[tagFile.parentPath].orEmpty()
            val taggedFile = pairSidecarWithMedia(tagFile.filename, siblings)

            localDatabaseDataSource.updateFileTags(
                connectionId,
                tagFile,
                taggedFile?.filename,
                if (taggedFile == null) emptyList() else readTags(connectionId, tagFile),
            )
        }

        Log.d(TAG, "Scanned ${tagFiles.size} sidecars")
        return tagFiles.size
    }

    private fun readTags(connectionId: Int, tagFile: LocalFile): List<String> {
        val handle = fileRepository.openFileDirectly(connectionId, tagFile.fullPath())
        val xmpMeta = XMPMetaFactory.parse(handle.inputStream)

        // XMP arrays are 1-indexed.
        return (1..xmpMeta.countArrayItems(DUBLIN_CORE_NAMESPACE, SUBJECT_PROPERTY)).map {
            xmpMeta.getArrayItem(DUBLIN_CORE_NAMESPACE, SUBJECT_PROPERTY, it).value
        }
    }

    private companion object {
        const val TAG = "XmpSidecarTagSource"
    }
}

/**
 * Finds the media file a sidecar describes, among the other files in its directory.
 *
 * Two conventions are in the wild and both are handled, full name first because it is
 * unambiguous:
 *
 *  - `photo.jpg.xmp` beside `photo.jpg` — darktable, digiKam. This one used to match
 *    *nothing*: the old pattern reduced it to `photo.jpg.%`, and no file is named that. Those
 *    sidecars were silently marked REMOVED and their tags thrown away.
 *  - `photo.xmp` beside `photo.jpg` — Lightroom, Bridge.
 *
 * The old implementation asked SQLite for `LIKE '<stem>.%'`, which is wrong twice over.
 * `_` is a single-character wildcard in LIKE, so `IMG_001.xmp` also matched `IMGX001.jpg` and
 * `IMG-001.mp4` — and underscores are in essentially every camera filename. `LIMIT 1` with no
 * `ORDER BY` then picked one of the matches arbitrarily, so the tags could land on a
 * different file between runs.
 *
 * [siblings] is matched in filename order, so a genuine ambiguity — `IMG_001.jpg` next to
 * `IMG_001.mp4`, one sidecar between them — resolves to the same file every time rather than
 * to whatever the query happened to return first.
 */
internal fun pairSidecarWithMedia(
    sidecarFilename: String,
    siblings: List<LocalFile>,
): LocalFile? {
    val stem = sidecarFilename.substringBeforeLast('.')
    val inNameOrder = siblings.sortedBy { it.filename }

    return inNameOrder.firstOrNull { it.filename == stem }
        ?: inNameOrder.firstOrNull { it.filename.substringBeforeLast('.') == stem }
}
