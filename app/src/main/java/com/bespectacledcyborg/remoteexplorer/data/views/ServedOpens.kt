package com.bespectacledcyborg.remoteexplorer.data.views

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

const val OPEN_IDLE_GAP_MILLIS = 30 * 60_000L

fun interface OpenListener {
    fun onServed(canonical: FileIdentity)

    companion object {
        val NONE = OpenListener { }
    }
}

class ServedOpens(
    private val scope: CoroutineScope,
    private val record: suspend (FileIdentity) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onFailure: (FileIdentity, Exception) -> Unit = { canonical, e ->
        Log.w("ServedOpens", "Could not record opening ${canonical.path}", e)
    },
) : OpenListener {
    private val lastServedAt = ConcurrentHashMap<FileIdentity, Long>()

    override fun onServed(canonical: FileIdentity) {
        val now = clock()
        val previous = lastServedAt.put(canonical, now)
        if (previous != null && now - previous < OPEN_IDLE_GAP_MILLIS) return
        scope.launch {
            try {
                record(canonical)
            } catch (e: Exception) {
                onFailure(canonical, e)
            }
        }
    }
}
