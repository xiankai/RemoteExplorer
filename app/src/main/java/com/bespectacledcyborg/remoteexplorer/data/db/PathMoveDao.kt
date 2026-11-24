package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.bespectacledcyborg.remoteexplorer.data.parseFolderList

fun movedPath(path: String, from: String, to: String): String? = when {
    path == from -> to
    path.startsWith("$from/") -> to + path.substring(from.length)
    else -> null
}

@Entity(
    primaryKeys = ["connectionId", "parentPath", "filename"],
    foreignKeys = [
        ForeignKey(
            entity = Connection::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingMove(
    val connectionId: Int,
    val parentPath: String,
    val filename: String,
    val movedTo: String,
    val movedAt: Long,
)

data class PathMove(
    val connectionId: Int,
    val from: String,
    val to: String,
) {
    val fromParent get() = from.substringBeforeLast('/', "")
    val fromName get() = from.substringAfterLast('/')
    val toParent get() = to.substringBeforeLast('/', "")
    val toName get() = to.substringAfterLast('/')
}

@Dao
abstract class PathMoveDao {
    @Transaction
    open suspend fun carry(canonical: PathMove, framesToInvalidate: List<PathMove>, pending: PendingMove?) {
        carryUserData(canonical)
        carryTagMirror(canonical)
        (framesToInvalidate + canonical).distinct().forEach { invalidateListings(it) }
        removePending(canonical.connectionId, canonical.toParent, canonical.toName)
        pending?.let { recordPending(it) }
        rewriteFirstPageFolders(listOf(canonical) + framesToInvalidate)
    }

    @Transaction
    open suspend fun invalidateFolders(frames: List<Pair<Int, String>>) {
        frames.distinct().forEach { (connectionId, path) -> forgetSync(connectionId, path) }
    }

    private suspend fun carryUserData(move: PathMove) = with(move) {
        moveFileViewEntry(connectionId, fromParent, fromName, toParent, toName)
        moveFileViewSubtree(connectionId, from, to)
        moveFolderSorts(connectionId, from, to)
        moveCoverChoices(connectionId, from, to)
    }

    private suspend fun carryTagMirror(move: PathMove) = with(move) {
        moveFileTagEntry(connectionId, fromParent, fromName, toParent, toName)
        moveFileTagSubtree(connectionId, from, to)
        moveDirTags(connectionId, from, to)
        moveIndexedDirectories(connectionId, from, to)
        moveAnnotationKinds(connectionId, from, to)
        moveAnnotations(connectionId, from, to)
    }

    private suspend fun invalidateListings(move: PathMove) = with(move) {
        forgetListedEntry(connectionId, fromParent, fromName)
        forgetListedSubtree(connectionId, from)
        forgetSyncSubtree(connectionId, from)
        forgetSync(connectionId, fromParent)
        forgetSync(connectionId, toParent)
    }

    private suspend fun rewriteFirstPageFolders(moves: List<PathMove>) {
        val folders = parseFolderList(firstPageFolders() ?: return)
        val moved = folders.map { folder ->
            moves.firstNotNullOfOrNull { move -> movedPath(folder, move.from, move.to) } ?: folder
        }
        if (moved != folders) setFirstPageFolders(moved.distinct().joinToString("\n"))
    }

    @Query("SELECT firstPageFolders FROM settings LIMIT 1")
    protected abstract suspend fun firstPageFolders(): String?

    @Query("UPDATE settings SET firstPageFolders = :folders")
    protected abstract suspend fun setFirstPageFolders(folders: String)

    @Query(
        """UPDATE OR REPLACE FileView SET parentPath = :toParent, filename = :toName
        WHERE connectionId = :connectionId AND parentPath = :fromParent AND filename = :fromName"""
    )
    protected abstract suspend fun moveFileViewEntry(
        connectionId: Int, fromParent: String, fromName: String, toParent: String, toName: String,
    )

    @Query(
        """UPDATE OR REPLACE FileView SET parentPath = :to || substr(parentPath, length(:from) + 1)
        WHERE connectionId = :connectionId
        AND (parentPath = :from OR substr(parentPath, 1, length(:from) + 1) = :from || '/')"""
    )
    protected abstract suspend fun moveFileViewSubtree(connectionId: Int, from: String, to: String)

    @Query(
        """UPDATE OR REPLACE FolderSort SET path = :to || substr(path, length(:from) + 1)
        WHERE connectionId = :connectionId
        AND (path = :from OR substr(path, 1, length(:from) + 1) = :from || '/')"""
    )
    protected abstract suspend fun moveFolderSorts(connectionId: Int, from: String, to: String)

    @Query(
        """UPDATE OR REPLACE FolderCoverChoice SET path = :to || substr(path, length(:from) + 1)
        WHERE connectionId = :connectionId
        AND (path = :from OR substr(path, 1, length(:from) + 1) = :from || '/')"""
    )
    protected abstract suspend fun moveCoverChoices(connectionId: Int, from: String, to: String)

    @Query(
        """UPDATE OR REPLACE FileTagRelation SET parentPath = :toParent, filename = :toName
        WHERE connectionId = :connectionId AND parentPath = :fromParent AND filename = :fromName"""
    )
    protected abstract suspend fun moveFileTagEntry(
        connectionId: Int, fromParent: String, fromName: String, toParent: String, toName: String,
    )

    @Query(
        """UPDATE OR REPLACE FileTagRelation SET parentPath = :to || substr(parentPath, length(:from) + 1)
        WHERE connectionId = :connectionId
        AND (parentPath = :from OR substr(parentPath, 1, length(:from) + 1) = :from || '/')"""
    )
    protected abstract suspend fun moveFileTagSubtree(connectionId: Int, from: String, to: String)

    @Query(
        """UPDATE OR REPLACE DirTagRelation SET parentPath = :to || substr(parentPath, length(:from) + 1)
        WHERE connectionId = :connectionId
        AND (parentPath = :from OR substr(parentPath, 1, length(:from) + 1) = :from || '/')"""
    )
    protected abstract suspend fun moveDirTags(connectionId: Int, from: String, to: String)

    @Query(
        """UPDATE OR REPLACE IndexedDirectory SET parentPath = :to || substr(parentPath, length(:from) + 1)
        WHERE connectionId = :connectionId
        AND (parentPath = :from OR substr(parentPath, 1, length(:from) + 1) = :from || '/')"""
    )
    protected abstract suspend fun moveIndexedDirectories(connectionId: Int, from: String, to: String)

    @Query(
        """UPDATE OR REPLACE DirAnnotationKind SET parentPath = :to || substr(parentPath, length(:from) + 1)
        WHERE connectionId = :connectionId
        AND (parentPath = :from OR substr(parentPath, 1, length(:from) + 1) = :from || '/')"""
    )
    protected abstract suspend fun moveAnnotationKinds(connectionId: Int, from: String, to: String)

    @Query(
        """UPDATE OR REPLACE DirAnnotation SET parentPath = :to || substr(parentPath, length(:from) + 1)
        WHERE connectionId = :connectionId
        AND (parentPath = :from OR substr(parentPath, 1, length(:from) + 1) = :from || '/')"""
    )
    protected abstract suspend fun moveAnnotations(connectionId: Int, from: String, to: String)

    @Query("DELETE FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    protected abstract suspend fun forgetListedEntry(connectionId: Int, parentPath: String, filename: String)

    @Query(
        """DELETE FROM LocalFile WHERE connectionId = :connectionId
        AND (parentPath = :path OR substr(parentPath, 1, length(:path) + 1) = :path || '/')"""
    )
    protected abstract suspend fun forgetListedSubtree(connectionId: Int, path: String)

    @Query(
        """DELETE FROM DirectorySync WHERE connectionId = :connectionId
        AND (parentPath = :path OR substr(parentPath, 1, length(:path) + 1) = :path || '/')"""
    )
    protected abstract suspend fun forgetSyncSubtree(connectionId: Int, path: String)

    @Query("DELETE FROM DirectorySync WHERE connectionId = :connectionId AND parentPath = :parentPath")
    protected abstract suspend fun forgetSync(connectionId: Int, parentPath: String)

    @Upsert
    protected abstract suspend fun recordPending(pending: PendingMove)

    @Query("DELETE FROM PendingMove WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    protected abstract suspend fun removePending(connectionId: Int, parentPath: String, filename: String)

    @Query("SELECT filename FROM PendingMove WHERE connectionId = :connectionId AND parentPath = :parentPath")
    abstract suspend fun pendingNames(connectionId: Int, parentPath: String): List<String>

    @Transaction
    open suspend fun settleListed(connectionId: Int, parentPath: String, listed: Set<String>, expiredBefore: Long) {
        pending(connectionId, parentPath)
            .filter { it.filename !in listed || it.movedAt < expiredBefore }
            .forEach { removePending(it.connectionId, it.parentPath, it.filename) }
    }

    @Query("SELECT * FROM PendingMove WHERE connectionId = :connectionId AND parentPath = :parentPath")
    protected abstract suspend fun pending(connectionId: Int, parentPath: String): List<PendingMove>
}
