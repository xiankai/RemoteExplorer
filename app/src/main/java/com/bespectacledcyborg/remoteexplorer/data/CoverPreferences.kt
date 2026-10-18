package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.CoverMode
import com.bespectacledcyborg.remoteexplorer.data.db.CoverShuffle
import com.bespectacledcyborg.remoteexplorer.data.db.FolderCoverChoice
import com.bespectacledcyborg.remoteexplorer.data.db.FolderCoverChoiceDao
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val EXAMPLEBOORU = "gallery-dl/examplebooru"
private val MANGA_TAGS = setOf("manga", "doujinshi")

internal fun isMangaPath(path: String): Boolean =
    path.trim('/').let { it == EXAMPLEBOORU || it.startsWith("$EXAMPLEBOORU/") }

internal fun isMangaTag(tag: String): Boolean =
    tag.split('/', ':').last().trim().lowercase() in MANGA_TAGS

data class ChosenCover(val applied: CoverMode, val order: CoverOrder)

class CoverPreferences(
    private val choices: FolderCoverChoiceDao,
    private val shuffleSetting: suspend () -> CoverShuffle,
    private val pairings: () -> Pairings,
    private val directoryTags: suspend (connectionId: Int, path: String) -> List<String>,
    private val aliasCanonicals: suspend (connectionId: Int, tags: List<String>) -> List<String>,
    private val newSeed: () -> Long = { Random.nextLong() },
) {
    suspend fun coverFor(
        connectionId: Int,
        path: String,
        sorted: CoverOrder,
        visitSeed: Long,
    ): ChosenCover {
        val applied = appliedChoice(connectionId, path)
        val order = when (applied.mode) {
            CoverMode.DEFAULT -> sorted
            CoverMode.NEWEST -> CoverOrder.Newest
            CoverMode.SHUFFLE -> CoverOrder.Shuffled(applied.seed)
            CoverMode.SHUFFLE_EVERY_VISIT -> CoverOrder.Shuffled(visitSeed)
        }
        return ChosenCover(applied.mode, order)
    }

    private suspend fun appliedChoice(connectionId: Int, path: String): FolderCoverChoice {
        val canonical = pairings().canonicalize(connectionId, path)
        val unchosen = FolderCoverChoice(canonical.connectionId, canonical.path, CoverMode.DEFAULT, 0L)
        if (keepsFirstPage(connectionId, path)) return unchosen
        val saved = choices.get(canonical.connectionId, canonical.path) ?: unchosen
        val shuffle = shuffleSetting()
        return when {
            saved.mode == CoverMode.NEWEST -> saved
            shuffle == CoverShuffle.OFF -> unchosen
            saved.mode != CoverMode.DEFAULT -> saved
            shuffle == CoverShuffle.ALL -> saved.copy(mode = CoverMode.SHUFFLE_EVERY_VISIT)
            else -> unchosen
        }
    }

    suspend fun choiceFor(connectionId: Int, path: String): CoverMode {
        val canonical = pairings().canonicalize(connectionId, path)
        return choices.get(canonical.connectionId, canonical.path)?.mode ?: CoverMode.DEFAULT
    }

    suspend fun choose(connectionId: Int, path: String, mode: CoverMode) {
        val canonical = pairings().canonicalize(connectionId, path)
        if (mode == CoverMode.DEFAULT) {
            choices.clear(canonical.connectionId, canonical.path)
        } else {
            choices.set(FolderCoverChoice(canonical.connectionId, canonical.path, mode, newSeed()))
        }
    }

    private val mangaFolders: MutableSet<FileIdentity> = ConcurrentHashMap.newKeySet()

    suspend fun keepsFirstPage(connectionId: Int, path: String): Boolean {
        val canonical = pairings().canonicalize(connectionId, path)
        val lineage = listOf(canonical) + ancestorsOf(canonical)
        val mangaFolder = lineage.firstOrNull { it in mangaFolders }
            ?: lineage.firstOrNull { isManga(it) }?.also { mangaFolders += it }
            ?: return false
        if (mangaFolder != canonical) mangaFolders += canonical
        return true
    }

    private fun ancestorsOf(folder: FileIdentity): List<FileIdentity> {
        val segments = Pairings.normalize(folder.path).split('/').filter { it.isNotEmpty() }
        return (segments.size - 1 downTo 1).map { folder.copy(path = segments.take(it).joinToString("/")) }
    }

    private suspend fun isManga(canonical: FileIdentity): Boolean {
        if (isMangaPath(canonical.path)) return true
        val tags = directoryTags(canonical.connectionId, canonical.path)
        if (tags.isEmpty()) return false
        return tags.any(::isMangaTag) ||
            aliasCanonicals(canonical.connectionId, tags).any(::isMangaTag)
    }
}
