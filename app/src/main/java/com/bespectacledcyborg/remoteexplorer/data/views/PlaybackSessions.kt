package com.bespectacledcyborg.remoteexplorer.data.views

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * How long a file can go without a byte served before its playback session ends. A paused
 * player stops requesting, so a pause longer than this ends the session. That is harmless:
 * the two halves cover disjoint spans and both get added.
 */
const val PLAYBACK_IDLE_GAP_MILLIS = 90_000L

/**
 * How often a running session's total is flushed, so a crash or a kill costs at most this
 * much rather than a whole film. Also how often idle sessions are noticed and closed.
 */
const val PLAYBACK_FLUSH_MILLIS = 30_000L

/** What the server tells the tracker as it writes a file's bytes out. */
fun interface PlaybackListener {
    /**
     * [first] to [last] (inclusive) of a [totalLength]-byte file went out. [canonical] is
     * what the file is known as, and [served] is where the bytes were read from.
     */
    fun onServed(canonical: FileIdentity, served: FileIdentity, first: Long, last: Long, totalLength: Long)

    companion object {
        val NONE = PlaybackListener { _, _, _, _, _ -> }
    }
}

/**
 * One playback of one file: the byte ranges served, merged, and when it started and was
 * last active.
 *
 * Merging is per session (#76, decision 6), and the ranges die with the session. Only the
 * converted total is ever stored, added to a running counter, so watching the same five
 * minutes every week keeps adding to that file's popularity.
 */
class PlaybackSession(val startedAt: Long, val totalLength: Long) {
    /** Disjoint, sorted, non-adjacent ranges. */
    private val ranges = ArrayList<LongRange>()

    var lastActiveAt: Long = startedAt
        private set

    /** What this session has already added to the counter. */
    var creditedSeconds: Double = 0.0

    fun add(first: Long, last: Long, at: Long) {
        if (last >= first) {
            var start = first
            var end = last
            val kept = ArrayList<LongRange>(ranges.size + 1)
            for (r in ranges) {
                // Overlapping or touching: fold it in. Bytes served twice (a rewind, a
                // player re-requesting after a seek) count once.
                if (r.last + 1 >= start && end + 1 >= r.first) {
                    start = minOf(start, r.first)
                    end = maxOf(end, r.last)
                } else {
                    kept += r
                }
            }
            kept += start..end
            kept.sortBy { it.first }
            ranges.clear()
            ranges.addAll(kept)
        }
        if (at > lastActiveAt) lastActiveAt = at
    }

    fun coveredBytes(): Long = ranges.sumOf { it.last - it.first + 1 }

    /**
     * Seconds watched: the byte estimate, clamped by the session's wall-clock span.
     *
     * `duration × covered / total` assumes a near-constant bitrate, which is wrong per file
     * on VBR and fine in aggregate, and an ordering is all this feeds. The clamp only ever
     * reduces. Playback cannot outrun real time, so a short clip pulled whole over loopback
     * in a second does not read as fully watched. With no duration (a container the platform
     * cannot read), the wall-clock span is what is left.
     */
    fun watchedSeconds(durationMillis: Long?): Double {
        val elapsed = (lastActiveAt - startedAt).coerceAtLeast(0) / 1000.0
        if (durationMillis == null || durationMillis <= 0 || totalLength <= 0) return elapsed
        val estimate = durationMillis / 1000.0 * coveredBytes() / totalLength
        return minOf(estimate, elapsed)
    }
}

/**
 * Turns bytes served through the loopback server into watched seconds (#76, decision 3).
 *
 * A session starts at the first byte served for a canonical file and ends after
 * [PLAYBACK_IDLE_GAP_MILLIS] with nothing more. While it runs, its total is flushed every
 * [PLAYBACK_FLUSH_MILLIS], and once more as it closes.
 *
 * Known limit: rewatching inside one session is under-counted. "Rewound and watched again"
 * and "the player re-fetched after a flush" are the same bytes going out twice, and traffic
 * alone cannot tell them apart. Across sessions a rewatch does add.
 */
class PlaybackSessions(
    private val scope: CoroutineScope,
    /** The file's duration, read from [served]'s container, or null when it cannot be read. */
    private val durationOf: suspend (served: FileIdentity) -> Long?,
    private val credit: suspend (canonical: FileIdentity, seconds: Double) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaybackListener {
    private sealed interface Duration {
        data object Pending : Duration
        data class Known(val millis: Long?) : Duration
    }

    private val sessions = ConcurrentHashMap<FileIdentity, PlaybackSession>()

    /** Per file and kept across sessions: a container's duration does not change between plays. */
    private val durations = ConcurrentHashMap<FileIdentity, Duration>()

    fun start() {
        scope.launch {
            while (isActive) {
                delay(PLAYBACK_FLUSH_MILLIS)
                sweep()
            }
        }
    }

    override fun onServed(
        canonical: FileIdentity,
        served: FileIdentity,
        first: Long,
        last: Long,
        totalLength: Long,
    ) {
        val now = clock()
        var ended: PlaybackSession? = null
        val session = sessions.compute(canonical) { _, existing ->
            if (existing != null && now - existing.lastActiveAt <= PLAYBACK_IDLE_GAP_MILLIS) {
                existing
            } else {
                ended = existing
                PlaybackSession(now, totalLength)
            }
        }!!
        synchronized(session) { session.add(first, last, now) }
        ended?.let { scope.launch { flush(canonical, it) } }

        if (durations.putIfAbsent(canonical, Duration.Pending) == null) {
            scope.launch {
                val millis = try {
                    durationOf(served)
                } catch (e: Exception) {
                    Log.d(TAG, "No duration for ${canonical.path}", e)
                    null
                }
                durations[canonical] = Duration.Known(millis)
            }
        }
    }

    /** Flushes every session, and closes the ones that have gone idle. */
    suspend fun sweep() {
        val now = clock()
        for ((canonical, session) in sessions) {
            val idle = now - session.lastActiveAt > PLAYBACK_IDLE_GAP_MILLIS
            if (idle) sessions.remove(canonical, session)
            flush(canonical, session, final = idle)
        }
    }

    private suspend fun flush(canonical: FileIdentity, session: PlaybackSession, final: Boolean = true) {
        val duration = durations[canonical]
        // Until the duration is in, the byte estimate cannot be made, and crediting the
        // wall clock now could not be taken back once the smaller estimate arrives. A final
        // flush goes ahead regardless.
        if (duration !is Duration.Known && !final) return
        val delta = synchronized(session) {
            val watched = session.watchedSeconds((duration as? Duration.Known)?.millis)
            val delta = watched - session.creditedSeconds
            if (delta > 0) session.creditedSeconds = watched
            delta
        }
        if (delta > 0) {
            try {
                credit(canonical, delta)
            } catch (e: Exception) {
                Log.w(TAG, "Could not record ${delta}s watched of ${canonical.path}", e)
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackSessions"
    }
}
