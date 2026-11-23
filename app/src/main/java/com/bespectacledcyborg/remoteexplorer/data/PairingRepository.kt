package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import com.bespectacledcyborg.remoteexplorer.data.db.PairingDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The pairings, held in memory so they can be asked synchronously.
 *
 * `canonicalize` runs where there is no coroutine to suspend in — building a URL for a
 * grid cell — and on every thumbnail request, so it cannot be a query each time. The table
 * is a handful of rows, and following it means an edit applies to the very next lookup.
 */
class PairingRepository(
    private val pairingDao: PairingDao,
) {
    private val snapshot = MutableStateFlow<Pairings?>(null)
    private val rows = MutableStateFlow<List<Pairing>>(emptyList())
    private val readRows = MutableStateFlow<List<Pairing>?>(null)

    /** The rows themselves, for the screen that edits them. */
    val pairings: StateFlow<List<Pairing>> = rows.asStateFlow()

    val loadedPairings: Flow<List<Pairing>> = readRows.filterNotNull()

    fun follow(scope: CoroutineScope) {
        scope.launch {
            pairingDao.getPairings().collect {
                rows.value = it
                readRows.value = it
                snapshot.value = Pairings(it)
            }
        }
    }

    /**
     * The pairings as of now. [Pairings.NONE] before the first read completes, which names
     * every file as itself — correct for a share, and for a local root only a missed dedup.
     */
    fun current(): Pairings = snapshot.value ?: Pairings.NONE

    /**
     * Suspends until the table has been read once. Opening a connection waits on this, so
     * no URL for it is built from the empty set — Sketch would cache that URL, and the
     * whole point is that it matches the remote's.
     */
    suspend fun awaitLoaded(): Pairings = snapshot.filterNotNull().first()

    suspend fun save(pairing: Pairing) = pairingDao.upsert(pairing)

    suspend fun delete(pairing: Pairing) = pairingDao.delete(pairing)
}
