package com.bespectacledcyborg.remoteexplorer.data.tags

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.util.zip.GZIPInputStream

class IndexLog<T>(
    val meta: String,
    val log: String,
    val compressedLog: String?,
    val parse: (String) -> T?,
) {
    companion object {
        val TAGS = IndexLog(TagIndex.META, TagIndex.LOG, TagIndex.COMPRESSED_LOG, ::parseTagIndexLine)
        val ANNOTATIONS = IndexLog(TagIndex.ANNOTATIONS_META, TagIndex.ANNOTATIONS, null, ::parseAnnotationLine)
    }
}

typealias TagIndexReader = IndexLogReader<TagIndexRecord>

fun TagIndexReader(files: TagIndexFiles): TagIndexReader = IndexLogReader(files, IndexLog.TAGS)

class IndexLogReader<T>(
    private val files: TagIndexFiles,
    private val source: IndexLog<T>,
) {

    suspend fun plan(connectionId: Int, state: TagIngestState?): TagIndexPlan {
        val meta = readMeta(connectionId) ?: return TagIndexPlan.NothingToDo
        if (meta.bytes <= 0L) return TagIndexPlan.NothingToDo

        if (state == null || state.generation != meta.generation) {
            return TagIndexPlan.FullPull(meta)
        }

        if (state.bytesRead > meta.bytes) {
            Log.w(TAG, "Stored offset ${state.bytesRead} is past ${meta.bytes}; re-pulling")
            return TagIndexPlan.FullPull(meta)
        }

        if (state.bytesRead == meta.bytes) return TagIndexPlan.NothingToDo
        return TagIndexPlan.Resume(meta, from = state.bytesRead)
    }

    suspend fun readMeta(connectionId: Int): TagIndexMeta? {
        val bytes = files.readWhole(connectionId, source.meta) ?: return null
        return runCatching {
            TagIndex.json.decodeFromString<TagIndexMeta>(bytes.decodeToString())
        }.getOrElse {
            Log.w(TAG, "Could not read ${source.meta}", it)
            null
        }
    }

    suspend fun read(
        connectionId: Int,
        plan: TagIndexPlan,
        onRecord: (T) -> Unit,
    ): TagIndexPlan {
        when (plan) {
            is TagIndexPlan.NothingToDo -> return plan

            is TagIndexPlan.FullPull -> {
                readFull(connectionId, plan.meta, onRecord)
                return plan
            }

            is TagIndexPlan.Resume -> {
                val stream = files.openRange(connectionId, source.log, plan.from, plan.meta.bytes)
                    ?: return plan
                val outcome = stream.use { readResumed(it, onRecord) }
                if (outcome == ResumeOutcome.COMPLETE) return plan

                Log.w(TAG, "Resume of ${source.log} from ${plan.from} landed mid-record; re-pulling")
                readFull(connectionId, plan.meta, onRecord)
                return TagIndexPlan.FullPull(plan.meta)
            }
        }
    }

    private suspend fun readFull(
        connectionId: Int,
        meta: TagIndexMeta,
        onRecord: (T) -> Unit,
    ) {
        val compressed = source.compressedLog?.let { files.openWhole(connectionId, it) }
        if (compressed != null) {
            compressed.use { GZIPInputStream(it).use { gzip -> readLines(gzip, onRecord) } }
            return
        }
        files.openRange(connectionId, source.log, 0L, meta.bytes)
            ?.use { readLines(it, onRecord) }
    }

    private fun readLines(stream: InputStream, onRecord: (T) -> Unit) {
        BufferedReader(stream.reader()).forEachLine { line ->
            source.parse(line)?.let(onRecord)
        }
    }

    private fun readResumed(
        stream: InputStream,
        onRecord: (T) -> Unit,
    ): ResumeOutcome {
        val lines = BufferedReader(stream.reader())

        var line = lines.readLine()
        while (line != null && line.isBlank()) line = lines.readLine()
        if (line == null) return ResumeOutcome.COMPLETE

        val first = source.parse(line) ?: return ResumeOutcome.MISALIGNED
        onRecord(first)

        while (true) {
            line = lines.readLine() ?: break
            source.parse(line)?.let(onRecord)
        }
        return ResumeOutcome.COMPLETE
    }

    private companion object {
        const val TAG = "IndexLogReader"
    }
}

/**
 * How a resume ended, internal to [TagIndexReader] because it is not a decision the caller
 * gets to make: [MISALIGNED] means the log is not the one the stored offset belongs to, and
 * the only correct response is to take the whole thing again.
 */
private enum class ResumeOutcome { COMPLETE, MISALIGNED }

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

    /**
     * A file read start to end: the gzipped copy, and the alias file.
     *
     * Not [readWhole], which is capped to the size a meta file can plausibly be — the alias
     * file is already past that, and is a stream consumer's business anyway.
     */
    suspend fun openWhole(connectionId: Int, path: String): InputStream?
}
