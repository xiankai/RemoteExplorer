package com.bespectacledcyborg.remoteexplorer.data.tags

import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.db.AnnotationDao
import com.bespectacledcyborg.remoteexplorer.data.db.AnnotationUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AnnotationFileTagSource(
    private val reader: IndexLogReader<AnnotationRecord>,
    private val annotationDao: AnnotationDao,
    private val dispatcher: CoroutineDispatcher,
) : TagSource {

    override suspend fun index(
        connectionId: Int,
        reindexAll: Boolean,
        limit: Int,
    ): TagIndexProgress {
        val state = if (reindexAll) null else withContext(dispatcher) {
            annotationDao.getIngest(connectionId)?.let { TagIngestState(it.generation, it.bytesRead) }
        }

        val plan = reader.plan(connectionId, state)
        if (plan is TagIndexPlan.NothingToDo) return TagIndexProgress.NOTHING

        val updates = mutableListOf<AnnotationUpdate>()
        val carried = reader.read(connectionId, plan) { record ->
            if (record.scope == TagScope.DIRECTORY) updates += record.toUpdate()
        }
        val meta = when (carried) {
            is TagIndexPlan.FullPull -> carried.meta
            is TagIndexPlan.Resume -> carried.meta
            TagIndexPlan.NothingToDo -> return TagIndexProgress.NOTHING
        }

        withContext(dispatcher) {
            annotationDao.apply(
                connectionId = connectionId,
                replace = carried is TagIndexPlan.FullPull,
                updates = updates,
                generation = meta.generation,
                bytesRead = meta.bytes,
            )
        }
        Log.i(TAG, "Applied ${updates.size} annotation records for connection $connectionId")
        return TagIndexProgress(updates.size, mayHaveMore = false)
    }

    private fun AnnotationRecord.toUpdate() = AnnotationUpdate(
        parentPath = path,
        id = id,
        text = text,
        language = language,
        derivedFrom = derivedFrom,
        supersedes = supersedes,
    )

    private companion object {
        const val TAG = "AnnotationFileTagSource"
    }
}
