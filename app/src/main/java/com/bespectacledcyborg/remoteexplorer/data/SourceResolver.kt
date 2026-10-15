package com.bespectacledcyborg.remoteexplorer.data

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides which copy of a file the server actually reads.
 *
 * A paired local copy when it has the file — faster than the share and there with no
 * network at all — and the remote otherwise. That covers both directions: a request made
 * while browsing the local root, and one made while browsing the share whose file happens
 * to be on the device too. A file missing from the copy (Syncthing has not caught up, or
 * the folder is excluded from sync) falls through to the share.
 *
 * The connection a request names may never have been opened in this process — browsing a
 * local root does not open the share it is paired with — so [connect] opens whichever one
 * the answer lands on, and is expected to be a no-op for one that is already open.
 */
class SourceResolver(
    private val files: FileDataSource,
    private val pairings: () -> Pairings,
    private val connect: suspend (ConnectionId) -> Unit,
    // Injected so a JVM test can reach the branch that logs: android.util.Log throws there.
    private val onUnreadable: (FileIdentity, Exception) -> Unit = { copy, e ->
        Log.w(TAG, "Local copy ${copy.path} on ${copy.connectionId} unreadable", e)
    },
) {
    /** Where to read [canonical] from. Throws only when the remote cannot be reached either. */
    suspend fun resolve(canonical: FileIdentity): FileIdentity {
        for (copy in pairings().localCopiesOf(canonical)) {
            // Opened and closed to ask whether it is there. For a local file that is a stat
            // and an open, which is noise next to the read that follows.
            val present = try {
                connect(copy.connectionId)  // stack-reviewer-ignore: deliberately inside the try. A local root that cannot be opened — all-files access revoked (AllFilesAccessMissing), the card ejected, a pairing snapshot a moment older than a deleted connection — is exactly the case that must fall through to the share rather than fail a request the share can answer. It is not silent: anything but a missing file goes to onUnreadable, which logs it.
                files.openFile(copy.connectionId, copy.path).close()
                true
            } catch (e: CancellationException) {
                // The request went away. Carrying on to the share would do its work anyway.
                throw e
            } catch (e: Exception) {
                // Missing is the ordinary case and says nothing. Anything else — the card
                // ejected, access revoked — is worth a line, and is still no reason to fail
                // a request the share can answer.
                if (e !is java.io.FileNotFoundException) onUnreadable(copy, e)
                false
            }
            if (present) return copy
        }

        connect(canonical.connectionId)
        return canonical
    }

    private companion object {
        const val TAG = "SourceResolver"
    }
}

/**
 * Opens a connection the first time something needs it, and never twice at once.
 *
 * The server can be asked for a file on a connection nobody opened in this process: the
 * share a local root is paired with, while browsing only the root. A grid asks forty times
 * at once, and [SambaFileDataSource.connect] opens a whole new client every time it is
 * called, so without the lock and the check this is forty logins. A connect that fails is
 * not remembered — the next request tries again, which is what a share coming back wants.
 */
class ConnectionOpener(
    private val files: RoutingFileDataSource,
    private val lookUp: suspend (ConnectionId) -> Connection?,
    private val open: suspend (Connection) -> Unit,
) {
    private val lock = Mutex()

    suspend fun ensureOpen(connectionId: ConnectionId) {
        if (files.isConnected(connectionId)) return
        lock.withLock {
            if (files.isConnected(connectionId)) return
            val connection = lookUp(connectionId)
                ?: throw UnknownConnectionException(connectionId, "does not exist")
            open(connection)
        }
    }
}
