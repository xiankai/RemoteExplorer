package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.PathMove
import com.bespectacledcyborg.remoteexplorer.data.db.PathMoveDao
import com.bespectacledcyborg.remoteexplorer.data.db.PendingMove
import com.bespectacledcyborg.remoteexplorer.data.db.movedPath

enum class Refusal {
    INVALID_NAME,
    NOT_FOUND,
    NAS_ONLY,
    NAME_TAKEN,
    INTO_ITSELF,
    CROSSES_PAIRING,
    HOLDS_PAIRED_FOLDER,
}

sealed interface FileOperationResult {
    data class Done(val connectionId: ConnectionId, val path: String) : FileOperationResult
    data class Refused(val reason: Refusal) : FileOperationResult
}

const val LARGE_MOVE_FILES = 2_000
const val LARGE_MOVE_BYTES = 2L * 1024 * 1024 * 1024

fun EntrySize.isLarge(): Boolean =
    !complete || files >= LARGE_MOVE_FILES || bytes >= LARGE_MOVE_BYTES

object EntryNames {
    private val forbidden = setOf('/', '\\', '<', '>', ':', '"', '|', '?', '*')

    fun isValid(name: String): Boolean =
        name.isNotBlank() &&
            name.none { it in forbidden || it.code < 0x20 } &&
            !name.startsWith(".") &&
            !name.endsWith(".") &&
            !name.endsWith(" ") &&
            !isSyncthingInternal(name)
}

interface ThumbnailMover {
    suspend fun move(moves: List<Pair<FileIdentity, FileIdentity>>)
}

class FileOperations(
    private val local: LocalFileDataSource,
    private val isLocalRoot: suspend (ConnectionId) -> Boolean,
    private val ensureOpen: suspend (ConnectionId) -> Unit,
    private val pairings: () -> Pairings,
    private val listedNames: suspend (ConnectionId, String) -> List<String>,
    private val paths: PathMoveDao,
    private val thumbnails: ThumbnailMover,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun createFolder(connectionId: ConnectionId, parentPath: String, name: String): FileOperationResult {
        if (!EntryNames.isValid(name)) return refuse(Refusal.INVALID_NAME)
        val parent = localSideOf(connectionId, parentPath, mustBeFolder = true)
            ?: return refuse(Refusal.NAS_ONLY)
        if (isTaken(parent, name, except = null)) return refuse(Refusal.NAME_TAKEN)

        val created = parent.child(name)
        local.createFolder(created.connectionId, created.path)
        paths.invalidateFolders(namesOf(parent).map { it.connectionId to it.path })
        return FileOperationResult.Done(connectionId, frameOf(connectionId, created).path)
    }

    suspend fun rename(connectionId: ConnectionId, path: String, newName: String): FileOperationResult =
        relocate(connectionId, path, Pairings.normalize(path).substringBeforeLast('/', ""), newName)

    suspend fun move(connectionId: ConnectionId, path: String, toFolder: String): FileOperationResult =
        relocate(connectionId, path, Pairings.normalize(toFolder), Pairings.normalize(path).substringAfterLast('/'))

    suspend fun sizeOf(connectionId: ConnectionId, path: String): EntrySize? {
        val entry = localSideOf(connectionId, path, mustBeFolder = false) ?: return null
        return local.measure(entry.connectionId, entry.path, LARGE_MOVE_FILES)
    }

    private suspend fun relocate(
        connectionId: ConnectionId,
        path: String,
        toFolder: String,
        name: String,
    ): FileOperationResult {
        if (!EntryNames.isValid(name)) return refuse(Refusal.INVALID_NAME)
        if (Pairings.normalize(path).isEmpty()) return refuse(Refusal.NOT_FOUND)
        val source = localSideOf(connectionId, path, mustBeFolder = false)
            ?: return refuse(if (listedHere(connectionId, path)) Refusal.NAS_ONLY else Refusal.NOT_FOUND)
        val folder = localSideOf(connectionId, toFolder, mustBeFolder = true)
            ?: return refuse(Refusal.NAS_ONLY)
        if (folder.connectionId != source.connectionId) return refuse(Refusal.CROSSES_PAIRING)
        if (Pairings.relativeTo(folder.path, source.path) != null) return refuse(Refusal.INTO_ITSELF)

        val target = folder.child(name)
        if (target == source) return FileOperationResult.Done(connectionId, frameOf(connectionId, target).path)
        val current = pairings()
        if (current.holdsPairedFolder(source.connectionId, source.path)) return refuse(Refusal.HOLDS_PAIRED_FOLDER)
        if (current.pairingOf(source.connectionId, source.path) != current.pairingOf(target.connectionId, target.path)) {
            return refuse(Refusal.CROSSES_PAIRING)
        }
        val sameEntry = source.path.substringBeforeLast('/', "") == folder.path
        if (isTaken(folder, name, except = source.path.substringAfterLast('/').takeIf { sameEntry })) {
            return refuse(Refusal.NAME_TAKEN)
        }

        local.move(source.connectionId, source.path, target.path)
        carry(source, target)
        return FileOperationResult.Done(connectionId, frameOf(connectionId, target).path)
    }

    private suspend fun carry(source: FileIdentity, target: FileIdentity) {
        val current = pairings()
        val canonicalFrom = current.canonicalize(source.connectionId, source.path)
        val canonicalTo = current.canonicalize(target.connectionId, target.path)
        val canonical = PathMove(canonicalFrom.connectionId, canonicalFrom.path, canonicalTo.path)
        val otherCopies = current.localCopiesOf(canonicalFrom)
            .filter { it.connectionId != source.connectionId }
            .mapNotNull { copy ->
                current.localCopiesOf(canonicalTo)
                    .firstOrNull { it.connectionId == copy.connectionId }
                    ?.let { PathMove(copy.connectionId, copy.path, it.path) }
            }
        val frames = (listOf(PathMove(source.connectionId, source.path, target.path)) + otherCopies)
            .filter { it != canonical }
        val pending = if (canonicalFrom.connectionId != source.connectionId) {
            PendingMove(
                connectionId = canonical.connectionId,
                parentPath = canonical.fromParent,
                filename = canonical.fromName,
                movedTo = canonical.to,
                movedAt = clock(),
            )
        } else {
            null
        }
        paths.carry(canonical, frames, pending)
        thumbnails.move(thumbnailMoves(source, target))
    }

    private fun thumbnailMoves(source: FileIdentity, target: FileIdentity): List<Pair<FileIdentity, FileIdentity>> {
        val current = pairings()
        return local.filesUnder(target.connectionId, target.path).mapNotNull { movedFile ->
            val before = movedPath(movedFile, target.path, source.path) ?: return@mapNotNull null
            current.canonicalize(source.connectionId, before) to
                current.canonicalize(target.connectionId, movedFile)
        }.filter { (from, to) -> from != to }
    }

    private suspend fun localSideOf(connectionId: ConnectionId, path: String, mustBeFolder: Boolean): FileIdentity? {
        val normalized = Pairings.normalize(path)
        val current = pairings()
        val canonical = current.canonicalize(connectionId, normalized)
        val candidates = buildList {
            if (isLocalRoot(connectionId)) add(FileIdentity(connectionId, normalized))
            addAll(current.localCopiesOf(canonical))
        }.distinct()
        return candidates.firstOrNull { candidate ->
            runCatching { ensureOpen(candidate.connectionId) }.isSuccess &&
                if (mustBeFolder) {
                    local.isFolder(candidate.connectionId, candidate.path)
                } else {
                    local.exists(candidate.connectionId, candidate.path)
                }
        }
    }

    private suspend fun isTaken(folder: FileIdentity, name: String, except: String?): Boolean {
        val wanted = name.lowercase()
        val onDisk = local.namesIn(folder.connectionId, folder.path)
        val listed = namesOf(folder).flatMap { listedNames(it.connectionId, it.path) }
        return (onDisk + listed).any { it.lowercase() == wanted && it != except }
    }

    private suspend fun listedHere(connectionId: ConnectionId, path: String): Boolean {
        val normalized = Pairings.normalize(path)
        return normalized.substringAfterLast('/') in
            listedNames(connectionId, normalized.substringBeforeLast('/', ""))
    }

    private fun namesOf(identity: FileIdentity): List<FileIdentity> =
        listOf(identity, pairings().canonicalize(identity.connectionId, identity.path)).distinct()

    private fun frameOf(connectionId: ConnectionId, identity: FileIdentity): FileIdentity =
        pairings().inFrameOf(connectionId, pairings().canonicalize(identity.connectionId, identity.path))

    private fun FileIdentity.child(name: String) = copy(path = Pairings.join(path, name))

    private fun refuse(reason: Refusal) = FileOperationResult.Refused(reason)
}
