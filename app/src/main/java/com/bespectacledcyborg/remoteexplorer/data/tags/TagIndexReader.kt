package com.bespectacledcyborg.remoteexplorer.data.tags

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Gets the index off the share, in whichever of the two ways is cheaper.
 *
 * The share is reached through [TagIndexFiles] rather than smbj directly, so the decision
 * logic here — which is all of the interesting part — can be tested against a fake without
 * an SMB server.
 */
class TagIndexReader(
    private val files: TagIndexFiles,
) {

    /**
     * How much of the log to read, given what was ingested last time.
     *
     * Three outcomes, and which one applies is decided entirely by the meta file:
     *
     *  - **Nothing there.** No meta, or a log that has not grown. Ingest is already current.
     *  - **Resume.** Same generation, so the log is the same append-only log and everything
     *    before [ingestedBytes] has already been seen. Read the tail.
     *  - **Full re-pull.** A different generation means the generator rebuilt or changed
     *    schema, so the stored offset points into a file that no longer exists in that form.
     *    Take the whole thing, compressed, and start the ingest over.
     */
    suspend fun plan(connectionId: Int, state: TagIngestState?): TagIndexPlan {
        val meta = readMeta(connectionId) ?: return TagIndexPlan.NothingToDo
        if (meta.bytes <= 0L) return TagIndexPlan.NothingToDo

        if (state == null || state.generation != meta.generation) {
            return TagIndexPlan.FullPull(meta)
        }

        // Clamped rather than trusted: a stored offset past the end means the log was
        // replaced without the generation changing, which is not supposed to happen — and
        // reading from beyond the end would quietly ingest nothing forever rather than
        // noticing. Starting over costs one transfer and cannot get stuck.
        if (state.bytesRead > meta.bytes) {
            Log.w(TAG, "Stored offset ${state.bytesRead} is past ${meta.bytes}; re-pulling")
            return TagIndexPlan.FullPull(meta)
        }

        if (state.bytesRead == meta.bytes) return TagIndexPlan.NothingToDo
        return TagIndexPlan.Resume(meta, from = state.bytesRead)
    }

    /** The index's own description of itself, or null when there is no index on this share. */
    suspend fun readMeta(connectionId: Int): TagIndexMeta? {
        val bytes = files.readWhole(connectionId, TagIndex.META) ?: return null
        return runCatching {
            TagIndex.json.decodeFromString<TagIndexMeta>(bytes.decodeToString())
        }.getOrElse {
            // A meta file that cannot be read is indistinguishable from none, on purpose:
            // both mean there is nothing here this build can use, and neither is worth
            // surfacing to someone browsing a share.
            Log.w(TAG, "Could not read ${TagIndex.META}", it)
            null
        }
    }

    /**
     * Reads the records [plan] calls for, handing each to [onRecord].
     *
     * Streamed rather than collected: the compressed log is the whole library, and the
     * point of ingesting it is to put it in Room, not to hold it twice in memory first.
     */
    suspend fun read(
        connectionId: Int,
        plan: TagIndexPlan,
        onRecord: (TagIndexRecord) -> Unit,
    ) {
        when (plan) {
            is TagIndexPlan.NothingToDo -> return

            is TagIndexPlan.FullPull -> {
                val compressed = files.openCompressed(connectionId, TagIndex.COMPRESSED_LOG)
                if (compressed != null) {
                    compressed.use { GZIPInputStream(it).use { gzip -> readLines(gzip, onRecord) } }
                } else {
                    // The compressed copy is an optimisation, not a requirement. A generator
                    // that has not written it yet, or a share where it was deleted, still
                    // has the plain log — slower to pull, identical content.
                    Log.i(TAG, "No ${TagIndex.COMPRESSED_LOG}; reading the plain log")
                    files.openRange(connectionId, TagIndex.LOG, 0L, plan.meta.bytes)
                        ?.use { readLines(it, onRecord) }
                }
            }

            is TagIndexPlan.Resume ->
                files.openRange(connectionId, TagIndex.LOG, plan.from, plan.meta.bytes)
                    ?.use { readLines(it, onRecord) }
        }
    }

    private fun readLines(stream: InputStream, onRecord: (TagIndexRecord) -> Unit) {
        BufferedReader(stream.reader()).forEachLine { line ->
            parseTagIndexLine(line)?.let(onRecord)
        }
    }

    private companion object {
        const val TAG = "TagIndexReader"
    }
}

/** What a pass over the index should fetch. */
sealed interface TagIndexPlan {
    /** Already current, or there is no index here. */
    data object NothingToDo : TagIndexPlan

    /** Take everything and replace what was ingested. */
    data class FullPull(val meta: TagIndexMeta) : TagIndexPlan

    /** Take the bytes appended since last time. */
    data class Resume(val meta: TagIndexMeta, val from: Long) : TagIndexPlan
}

/**
 * How far a connection's ingest has got.
 *
 * [bytesRead] is a byte offset into the log rather than a record count, because that is what
 * makes the next pass a ranged read instead of a re-parse. It is only meaningful together
 * with [generation]: the same offset in a rebuilt log points somewhere arbitrary.
 */
data class TagIngestState(
    val generation: String,
    val bytesRead: Long,
)

/**
 * The share, as this reader needs to see it.
 *
 * Narrow on purpose. Everything below returns null when the file is not there, because a
 * share with no index is the ordinary case and not a failure — an implementation reserves
 * throwing for a share it cannot reach at all.
 */
interface TagIndexFiles {
    /** The whole of a small file, for the meta. */
    suspend fun readWhole(connectionId: Int, path: String): ByteArray?

    /** Bytes `[from, to)` of a file, which is the point of the whole design. */
    suspend fun openRange(connectionId: Int, path: String, from: Long, to: Long): InputStream?

    /** A file read start to end, for the gzipped copy. */
    suspend fun openCompressed(connectionId: Int, path: String): InputStream?
}
