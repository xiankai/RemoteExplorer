package com.bespectacledcyborg.remoteexplorer.data.tags

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.RemoteRandomAccess
import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * [TagIndexFiles] over SMB.
 *
 * The ranged read is the whole reason the index is worth having, so it goes through
 * [RemoteRandomAccess] rather than a stream that skips: skipping transfers everything before
 * the offset in order to throw it away, which on a resume is the entire log every time.
 */
class SmbTagIndexFiles(
    private val sambaFileDataSource: SambaFileDataSource,
) : TagIndexFiles {

    override suspend fun readWhole(connectionId: Int, path: String): ByteArray? =
        withReader(connectionId, path) { reader ->
            val length = reader.size()
            // The meta file is a handful of fields. Anything of a size that could not be is
            // not the file this thinks it is, and reading it into memory would be the
            // mistake rather than the symptom.
            if (length <= 0 || length > MAX_META_BYTES) {
                Log.w(TAG, "$path is $length bytes, which is not a meta file")
                return@withReader null
            }
            val buffer = ByteArray(length.toInt())
            var filled = 0
            while (filled < buffer.size) {
                val read = reader.readAt(filled.toLong(), buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            // All of it or none. A prefix of this file is not a smaller version of it: the
            // whole point of the meta is the byte length it states, and a truncated copy
            // either fails to parse — swallowed as "no index" — or parses into a length
            // that is not the log's, which every later pass then resumes against.
            if (filled == buffer.size) {
                buffer
            } else {
                Log.w(TAG, "$path stopped after $filled of $length bytes")
                null
            }
        }

    override suspend fun openRange(
        connectionId: Int,
        path: String,
        from: Long,
        to: Long,
    ): InputStream? {
        if (to <= from) return ByteArrayInputStream(ByteArray(0))
        val reader = openOrNull(connectionId, path) ?: return null
        return runCatching { rangeStream(reader, from, minOf(to, reader.size())) }
            .getOrElse {
                reader.close()
                throw it
            }
    }

    override suspend fun openCompressed(connectionId: Int, path: String): InputStream? {
        val reader = openOrNull(connectionId, path) ?: return null
        return runCatching { rangeStream(reader, 0L, reader.size()) }
            .getOrElse {
                reader.close()
                throw it
            }
    }

    /**
     * Opens the file, or returns null when it is not there.
     *
     * Absence is the ordinary case — most shares have never seen gallery-dl — so it comes
     * back as null. Anything else is a share that cannot be reached and propagates, because
     * treating an outage as "no tags" would silently empty an index that is actually fine.
     */
    private fun openOrNull(connectionId: Int, path: String): RemoteRandomAccess? {
        val reader = RemoteRandomAccess(sambaFileDataSource, connectionId, path)
        return try {
            // Forces the open, which is what turns a missing file into an exception here
            // rather than at the first read.
            reader.size()
            reader
        } catch (e: Exception) {
            reader.close()
            if (!isMissing(e)) throw e
            Log.d(TAG, "No $path on connection $connectionId")
            null
        }
    }

    private inline fun <T> withReader(
        connectionId: Int,
        path: String,
        block: (RemoteRandomAccess) -> T?,
    ): T? = openOrNull(connectionId, path)?.use(block)

    private fun rangeStream(reader: RemoteRandomAccess, from: Long, until: Long) =
        RangeStream(from, until, reader::readAt, reader::close)

    private companion object {
        const val TAG = "SmbTagIndexFiles"

        /** Generous by three orders of magnitude, and still far short of a real file. */
        const val MAX_META_BYTES = 64L * 1024

        /**
         * Whether this is "that file is not there" rather than "the share is not there".
         *
         * The distinction is the whole contract of the interface above, and it is worth
         * matching on smbj's own status enum rather than on the message: a share that has
         * gone away must propagate, and a string match that stopped matching would turn
         * every outage into an index that silently reads as empty.
         *
         * Checked through the cause chain because the open is wrapped on its way out of
         * RemoteRandomAccess.
         */
        fun isMissing(e: Exception): Boolean =
            generateSequence(e as Throwable?) { it.cause }
                .filterIsInstance<SMBApiException>()
                .any {
                    it.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
                            it.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
                            it.status == NtStatus.STATUS_NO_SUCH_FILE
                }
    }
}
