package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.PathMove
import com.bespectacledcyborg.remoteexplorer.data.db.PathMoveDao
import com.bespectacledcyborg.remoteexplorer.data.db.PendingMove
import com.bespectacledcyborg.remoteexplorer.data.db.movedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException

enum class Refusal {
    INVALID_NAME,
    NOT_FOUND,
    LOCAL_UNAVAILABLE,
    NAS_UNAVAILABLE,
    NAS_WRITE_REFUSED,
    NEEDS_NAS_CONFIRMATION,
    CROSSES_SIDES,
    SYNC_PENDING,
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
const val THUMBNAIL_CARRY_FILES = 20_000

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
    private val share: EntryTree,
    private val isLocalRoot: suspend (ConnectionId) -> Boolean,
    private val ensureOpen: suspend (ConnectionId) -> Unit,
    private val pairings: () -> Pairings,
    private val listedNames: suspend (ConnectionId, String) -> List<String>,
    private val paths: PathMoveDao,
    private val thumbnails: ThumbnailMover,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun createFolder(
        connectionId: ConnectionId,
        parentPath: String,
        name: String,
        confirmedOnNas: Boolean = false,
    ): FileOperationResult = withContext(dispatcher) {
        if (!EntryNames.isValid(name)) return@withContext refuse(Refusal.INVALID_NAME)
        refusingOnNasFailure {
            when (val parent = sideOf(connectionId, parentPath, mustBeFolder = true)) {
                is Side.Local -> createIn(connectionId, local, parent.identity, name)
                is Side.Nas ->
                    if (isPendingAlong(parent.identity)) refuse(Refusal.SYNC_PENDING)
                    else createIn(connectionId, share, parent.identity, name, needsConfirmation = !confirmedOnNas)
                is Side.Absent -> refuse(parent.reason)
            }
        }
    }

    private suspend fun createIn(
        connectionId: ConnectionId,
        tree: EntryTree,
        parent: FileIdentity,
        name: String,
        needsConfirmation: Boolean = false,
    ): FileOperationResult {
        if (isTaken(tree, parent, name, except = null)) return refuse(Refusal.NAME_TAKEN)
        if (needsConfirmation) return refuse(Refusal.NEEDS_NAS_CONFIRMATION)

        val created = parent.child(name)
        onTree(tree) { tree.createFolder(created.connectionId, created.path) }
        paths.invalidateFolders(framesOf(parent).map { it.connectionId to it.path })
        return FileOperationResult.Done(connectionId, frameOf(connectionId, created).path)
    }

    suspend fun rename(
        connectionId: ConnectionId,
        path: String,
        newName: String,
        confirmedOnNas: Boolean = false,
    ): FileOperationResult {
        if (!EntryNames.isValid(newName)) return refuse(Refusal.INVALID_NAME)
        val parent = Pairings.normalize(path).substringBeforeLast('/', "")
        return relocate(connectionId, path, parent, newName, confirmedOnNas)
    }

    suspend fun move(
        connectionId: ConnectionId,
        path: String,
        toFolder: String,
        confirmedOnNas: Boolean = false,
    ): FileOperationResult {
        val name = Pairings.normalize(path).substringAfterLast('/')
        return relocate(connectionId, path, Pairings.normalize(toFolder), name, confirmedOnNas)
    }

    suspend fun sizeOf(connectionId: ConnectionId, path: String): EntrySize? = withContext(dispatcher) {
        val entry = localSideOf(connectionId, path, mustBeFolder = false) as? Side.Local
            ?: return@withContext null
        local.measure(entry.identity.connectionId, entry.identity.path, LARGE_MOVE_FILES)
    }

    private suspend fun relocate(
        connectionId: ConnectionId,
        path: String,
        toFolder: String,
        name: String,
        confirmedOnNas: Boolean,
    ): FileOperationResult = withContext(dispatcher) {
        if (Pairings.normalize(path).isEmpty()) return@withContext refuse(Refusal.NOT_FOUND)
        refusingOnNasFailure {
            when (val source = sideOf(connectionId, path, mustBeFolder = false)) {
                is Side.Absent -> refuse(source.reason)
                is Side.Local -> when (val folder = localDestination(connectionId, toFolder)) {
                    is Side.Local -> relocateWithin(connectionId, local, source.identity, folder.identity, name)
                    is Side.Nas -> refuse(Refusal.CROSSES_SIDES)
                    is Side.Absent -> refuse(folder.reason)
                }
                is Side.Nas -> when (val folder = nasDestination(connectionId, toFolder)) {
                    is Side.Nas -> relocateOnNas(connectionId, source.identity, folder.identity, name, confirmedOnNas)
                    is Side.Local -> refuse(Refusal.CROSSES_SIDES)
                    is Side.Absent -> refuse(folder.reason)
                }
            }
        }
    }

    private suspend fun localDestination(connectionId: ConnectionId, toFolder: String): Side =
        when (val folder = localSideOf(connectionId, toFolder, mustBeFolder = true)) {
            is Side.Absent -> if (folder.reason == Refusal.NOT_FOUND && nasSideOf(connectionId, toFolder, true) is Side.Nas) {
                Side.Nas(FileIdentity(connectionId, toFolder))
            } else {
                folder
            }
            else -> folder
        }

    private suspend fun nasDestination(connectionId: ConnectionId, toFolder: String): Side =
        when (val folder = nasSideOf(connectionId, toFolder, mustBeFolder = true)) {
            is Side.Absent -> if (folder.reason == Refusal.NOT_FOUND && localSideOf(connectionId, toFolder, true) is Side.Local) {
                Side.Local(FileIdentity(connectionId, toFolder))
            } else {
                folder
            }
            else -> folder
        }

    private suspend fun relocateOnNas(
        connectionId: ConnectionId,
        source: FileIdentity,
        folder: FileIdentity,
        name: String,
        confirmed: Boolean,
    ): FileOperationResult {
        if (isPendingAlong(source) || isPendingAlong(folder)) return refuse(Refusal.SYNC_PENDING)
        return relocateWithin(connectionId, share, source, folder, name, needsConfirmation = !confirmed)
    }

    private suspend fun relocateWithin(
        connectionId: ConnectionId,
        tree: EntryTree,
        source: FileIdentity,
        folder: FileIdentity,
        name: String,
        needsConfirmation: Boolean = false,
    ): FileOperationResult {
        if (folder.connectionId != source.connectionId) return refuse(Refusal.CROSSES_PAIRING)
        if (Pairings.relativeTo(folder.path, source.path) != null) return refuse(Refusal.INTO_ITSELF)

        val target = folder.child(name)
        if (target == source) return FileOperationResult.Done(connectionId, frameOf(connectionId, target).path)
        if (holdsPairedFolder(source)) return refuse(Refusal.HOLDS_PAIRED_FOLDER)
        if (pairingAt(source) != pairingAt(target)) return refuse(Refusal.CROSSES_PAIRING)
        val sameEntry = source.path.substringBeforeLast('/', "") == folder.path
        if (isTaken(tree, folder, name, except = source.path.substringAfterLast('/').takeIf { sameEntry })) {
            return refuse(Refusal.NAME_TAKEN)
        }
        if (needsConfirmation) return refuse(Refusal.NEEDS_NAS_CONFIRMATION)

        val files = onTree(tree) { tree.filesUnder(source.connectionId, source.path, THUMBNAIL_CARRY_FILES) }
        withContext(NonCancellable) {
            onTree(tree) { tree.move(source.connectionId, source.path, target.path) }
            carry(source, target, files)
        }
        return FileOperationResult.Done(connectionId, frameOf(connectionId, target).path)
    }

    private suspend fun isPendingAlong(identity: FileIdentity): Boolean {
        val segments = Pairings.normalize(identity.path).split('/').filter { it.isNotEmpty() }
        return segments.indices.any { depth ->
            val parent = segments.take(depth).joinToString("/")
            paths.pendingNames(identity.connectionId, parent).any { it.equals(segments[depth], ignoreCase = true) }
        }
    }

    private fun pairingAt(identity: FileIdentity) =
        pairings().pairingOf(identity.connectionId, identity.path)
            ?: pairings().remotePairingOf(identity.connectionId, identity.path)

    private fun holdsPairedFolder(identity: FileIdentity) =
        pairings().holdsPairedFolder(identity.connectionId, identity.path) ||
            pairings().holdsRemotePairedFolder(identity.connectionId, identity.path)

    private suspend fun carry(source: FileIdentity, target: FileIdentity, files: List<String>) {
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
        thumbnails.move(thumbnailMoves(source, target, files))
    }

    private fun thumbnailMoves(
        source: FileIdentity,
        target: FileIdentity,
        files: List<String>,
    ): List<Pair<FileIdentity, FileIdentity>> {
        val current = pairings()
        return files.mapNotNull { before ->
            val after = movedPath(before, source.path, target.path) ?: return@mapNotNull null
            current.canonicalize(source.connectionId, before) to current.canonicalize(target.connectionId, after)
        }.filter { (from, to) -> from != to }
    }

    private sealed interface Side {
        data class Local(val identity: FileIdentity) : Side
        data class Nas(val identity: FileIdentity) : Side
        data class Absent(val reason: Refusal) : Side
    }

    private suspend fun sideOf(connectionId: ConnectionId, path: String, mustBeFolder: Boolean): Side =
        when (val side = localSideOf(connectionId, path, mustBeFolder)) {
            is Side.Absent -> if (side.reason == Refusal.NOT_FOUND) nasSideOf(connectionId, path, mustBeFolder) else side
            else -> side
        }

    private suspend fun localSideOf(connectionId: ConnectionId, path: String, mustBeFolder: Boolean): Side {
        val normalized = Pairings.normalize(path)
        val current = pairings()
        val canonical = current.canonicalize(connectionId, normalized)
        val candidates = buildList {
            if (isLocalRoot(connectionId)) add(FileIdentity(connectionId, normalized))
            addAll(current.localCopiesOf(canonical))
        }.distinct()
        var unavailable = false
        for (candidate in candidates) {
            if (!opens(candidate.connectionId)) {
                unavailable = true
                continue
            }
            val present = if (mustBeFolder) {
                local.isFolder(candidate.connectionId, candidate.path)
            } else {
                local.exists(candidate.connectionId, candidate.path)
            }
            if (present) return Side.Local(candidate)
        }
        return Side.Absent(if (unavailable) Refusal.LOCAL_UNAVAILABLE else Refusal.NOT_FOUND)
    }

    private suspend fun nasSideOf(connectionId: ConnectionId, path: String, mustBeFolder: Boolean): Side {
        val canonical = pairings().canonicalize(connectionId, Pairings.normalize(path))
        if (isLocalRoot(canonical.connectionId)) return Side.Absent(Refusal.NOT_FOUND)
        if (!opens(canonical.connectionId)) return Side.Absent(Refusal.NAS_UNAVAILABLE)
        val present = try {
            if (mustBeFolder) {
                share.isFolder(canonical.connectionId, canonical.path)
            } else {
                share.exists(canonical.connectionId, canonical.path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Side.Absent(Refusal.NAS_UNAVAILABLE)
        }
        return if (present) Side.Nas(canonical) else Side.Absent(Refusal.NOT_FOUND)
    }

    private suspend fun opens(connectionId: ConnectionId): Boolean = try {
        ensureOpen(connectionId)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun isTaken(tree: EntryTree, folder: FileIdentity, name: String, except: String?): Boolean {
        val wanted = name.lowercase()
        val onSide = onTree(tree) { tree.namesIn(folder.connectionId, folder.path) }
        val onCopies = framesOf(folder)
            .filter { it != folder && local.isFolder(it.connectionId, it.path) }
            .flatMap { local.namesIn(it.connectionId, it.path) }
        val listed = framesOf(folder).flatMap { listedNames(it.connectionId, it.path) }
        return (onSide + onCopies + listed).any { it.lowercase() == wanted && it != except }
    }

    private fun framesOf(identity: FileIdentity): List<FileIdentity> {
        val canonical = pairings().canonicalize(identity.connectionId, identity.path)
        return (listOf(identity, canonical) + pairings().localCopiesOf(canonical)).distinct()
    }

    private fun frameOf(connectionId: ConnectionId, identity: FileIdentity): FileIdentity =
        pairings().inFrameOf(connectionId, pairings().canonicalize(identity.connectionId, identity.path))

    private class NasFailure(val reason: Refusal) : Exception()

    private inline fun <T> onTree(tree: EntryTree, block: () -> T): T {
        if (tree === local) return block()
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccessDeniedException) {
            throw NasFailure(Refusal.NAS_WRITE_REFUSED)
        } catch (e: FileAlreadyExistsException) {
            throw NasFailure(Refusal.NAME_TAKEN)
        } catch (e: Exception) {
            throw NasFailure(Refusal.NAS_UNAVAILABLE)
        }
    }

    private inline fun refusingOnNasFailure(block: () -> FileOperationResult): FileOperationResult = try {
        block()
    } catch (e: NasFailure) {
        refuse(e.reason)
    }

    private fun FileIdentity.child(name: String) = copy(path = Pairings.join(path, name))

    private fun refuse(reason: Refusal) = FileOperationResult.Refused(reason)
}
