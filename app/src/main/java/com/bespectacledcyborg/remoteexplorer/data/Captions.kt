package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.AnnotatedDirectory
import com.bespectacledcyborg.remoteexplorer.data.db.AnnotationDao
import com.bespectacledcyborg.remoteexplorer.data.db.DirAnnotation
import com.bespectacledcyborg.remoteexplorer.data.db.LocalDatabaseDao
import com.bespectacledcyborg.remoteexplorer.data.tags.NARRATION
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val MANUAL_PREFIX = "manual:"
private const val ENGLISH = "en"

data class Caption(
    val text: String,
    val original: String?,
    val originalLanguage: String?,
)

fun pickCaption(rows: List<DirAnnotation>): Caption? {
    val superseded = rows
        .filter { it.id.startsWith(MANUAL_PREFIX) }
        .mapNotNull { it.supersedes }
        .toSet()
    val source = rows
        .filter { it.derivedFrom == null && it.id !in superseded }
        .sortedWith(compareBy({ !it.id.startsWith(MANUAL_PREFIX) }, { it.id }))
        .firstOrNull()
        ?: return null
    if (source.language == ENGLISH) return Caption(source.text, original = null, originalLanguage = null)

    val english = rows.firstOrNull { it.derivedFrom == source.id && it.language == ENGLISH }
        ?: return Caption(source.text, original = null, originalLanguage = source.language)
    return Caption(english.text, original = source.text, originalLanguage = source.language)
}

class CaptionRepository(
    private val annotationDao: AnnotationDao,
    private val localDatabaseDao: LocalDatabaseDao,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun captionedDirectories(): Set<AnnotatedDirectory> = withContext(dispatcher) {
        localDatabaseDao.getAnnotatedDirectories(NARRATION).toSet()
    }

    suspend fun captionFor(connectionId: Int, directory: String): Caption? = withContext(dispatcher) {
        pickCaption(annotationDao.getAnnotations(connectionId, directory))
    }
}
