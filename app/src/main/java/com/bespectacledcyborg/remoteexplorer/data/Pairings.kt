package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing

/** One file as the app names it: which connection, and where under it. */
data class FileIdentity(
    val connectionId: ConnectionId,
    val path: String,
)

/**
 * Which local folders are copies of which remote ones, and the two lookups everything else
 * needs from that.
 *
 * Matching is by relative path and nothing else: `Photos/beach.jpg` under a local root
 * paired with a share's `Photos` folder is taken to be that share's `Photos/beach.jpg`. No
 * content is hashed — Syncthing writes a file under a temporary name and renames it into
 * place, so one that exists under its final name is the whole file.
 */
class Pairings(pairings: List<Pairing>) {
    // Longest local folder first, so a pairing of `Photos/2024` beats one of `Photos` for a
    // file under both. Same for the remote side, for the reverse lookup.
    private val byLocal = pairings.sortedByDescending { normalize(it.localSubPath).length }
    private val byRemote = pairings.sortedByDescending { normalize(it.remoteSubPath).length }

    /**
     * The identity a file is known by everywhere that identifies one: the thumbnail cache,
     * the URL Sketch caches under, and (#51, step 4) tags.
     *
     * A file under a paired local folder is named as its remote twin, so the two share one
     * thumbnail and one set of tags. Anything else — a share, an unpaired local root, a
     * local file outside every paired folder — is already canonical and comes back as it
     * went in.
     */
    fun canonicalize(connectionId: ConnectionId, path: String): FileIdentity {
        for (pairing in byLocal) {
            if (pairing.localConnectionId != connectionId) continue
            val rest = relativeTo(path, pairing.localSubPath) ?: continue
            return FileIdentity(pairing.remoteConnectionId, join(pairing.remoteSubPath, rest))
        }
        return FileIdentity(connectionId, path)
    }

    /**
     * Where [canonical] would be if a paired local folder holds a copy — which is where it
     * should be read from when it does.
     *
     * Every candidate, most specific first, and none of them checked for existence: a file
     * can be missing from the copy for ordinary reasons (Syncthing has not caught up, or
     * the folder is excluded from sync), and deciding that is the caller's job.
     */
    fun localCopiesOf(canonical: FileIdentity): List<FileIdentity> =
        byRemote.mapNotNull { pairing ->
            if (pairing.remoteConnectionId != canonical.connectionId) return@mapNotNull null
            val rest = relativeTo(canonical.path, pairing.remoteSubPath) ?: return@mapNotNull null
            FileIdentity(pairing.localConnectionId, join(pairing.localSubPath, rest))
        }

    /**
     * [canonical] as the connection being browsed would name it — the reverse of
     * [canonicalize], for rows that come back in the remote's identity.
     *
     * Tags are the case: a local root indexes none of its own, so what a tag lists are the
     * share's rows, under the share's paths. Browsing a paired local root, those have to be
     * re-expressed as the local copy's paths before anything builds a URL or opens a folder
     * from them, or a pairing of the root with the share's `Photos` would ask for
     * `Photos/Photos/beach.jpg`.
     *
     * Unchanged when [canonical] already belongs to [connectionId] — so always, while a share
     * is being browsed, since no pairing has a share as its local side — and when the current
     * connection holds no copy of it.
     */
    fun inFrameOf(connectionId: ConnectionId, canonical: FileIdentity): FileIdentity {
        if (canonical.connectionId == connectionId) return canonical
        return localCopiesOf(canonical).firstOrNull { it.connectionId == connectionId }
            ?: canonical
    }

    /**
     * [files] re-expressed for [connectionId], by [inFrameOf].
     *
     * A row whose copy would be the local root itself — a tagged folder that *is* the paired
     * folder — keeps its own identity: a row with no name is not a thing the grid can show
     * or open.
     */
    fun inFrameOf(connectionId: ConnectionId, files: List<LocalFile>): List<LocalFile> =
        files.map { file ->
            val framed = inFrameOf(connectionId, FileIdentity(file.connectionId, file.fullPath()))
            if (framed.connectionId == file.connectionId || framed.path.isEmpty()) {
                file
            } else {
                // Split the way FileRepository.getFileThumbnailStatus does, which is the
                // inverse of LocalFile.fullPath: a file at the root has an empty parentPath.
                file.copy(
                    connectionId = framed.connectionId,
                    parentPath = framed.path.substringBeforeLast('/', ""),
                    filename = framed.path.substringAfterLast('/'),
                )
            }
        }

    fun pairingOf(connectionId: ConnectionId, path: String): Pairing? =
        byLocal.firstOrNull {
            it.localConnectionId == connectionId && relativeTo(path, it.localSubPath) != null
        }

    fun holdsPairedFolder(connectionId: ConnectionId, path: String): Boolean =
        byLocal.any {
            it.localConnectionId == connectionId && relativeTo(it.localSubPath, path) != null
        }

    companion object {
        val NONE = Pairings(emptyList())

        /**
         * [path] relative to [folder], or null when it is not inside it.
         *
         * Segment-wise, not by string prefix: `Photos2/a.jpg` is not under `Photos`.
         */
        internal fun relativeTo(path: String, folder: String): String? {
            val base = normalize(folder)
            val target = normalize(path)
            return when {
                base.isEmpty() -> target
                target == base -> ""
                target.startsWith("$base/") -> target.substring(base.length + 1)
                else -> null
            }
        }

        internal fun join(folder: String, rest: String): String =
            listOf(normalize(folder), rest).filter { it.isNotEmpty() }.joinToString("/")

        /** Paths here never carry a leading or trailing slash; see `LocalFile.fullPath`. */
        internal fun normalize(path: String): String = path.trim('/')
    }
}
