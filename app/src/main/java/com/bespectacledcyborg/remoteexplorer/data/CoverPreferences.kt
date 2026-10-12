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

class CoverPreferences(
    private val choices: FolderCoverChoiceDao,
    private val shuffleSetting: suspend () -> CoverShuffle,
    private val pairings: () -> Pairings,
    private val directoryTags: suspend (connectionId: Int, path: String) -> List<String>,
    private val aliasCanonicals: suspend (connectionId: Int, tags: List<String>) -> List<String>,
    private val newSeed: () -> Long = { Random.nextLong() },
) {
    suspend fun orderFor(
        connectionId: Int,
        path: String,
        sorted: CoverOrder,
        visitSeed: Long,
    ): CoverOrder {
        if (keepsFirstPage(connectionId, path)) return sorted
        val canonical = pairings().canonicalize(connectionId, path)
        val saved = choices.get(canonical.connectionId, canonical.path)
        val choice = saved?.mode ?: CoverMode.DEFAULT
        val shuffle = shuffleSetting()
        return when {
            choice == CoverMode.NEWEST -> CoverOrder.Newest
            shuffle == CoverShuffle.OFF -> sorted
            choice == CoverMode.SHUFFLE -> CoverOrder.Shuffled(saved?.seed ?: 0L)
            choice == CoverMode.SHUFFLE_EVERY_VISIT -> CoverOrder.Shuffled(visitSeed)
            shuffle == CoverShuffle.ALL -> CoverOrder.Shuffled(visitSeed)
            else -> sorted
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

    private val mangaFolders = ConcurrentHashMap<FileIdentity, Boolean>()

    suspend fun keepsFirstPage(connectionId: Int, path: String): Boolean {
        val canonical = pairings().canonicalize(connectionId, path)
        mangaFolders[canonical]?.let { return it }
        return isManga(canonical).also { mangaFolders[canonical] = it }
    }

    private suspend fun isManga(canonical: FileIdentity): Boolean {
        if (isMangaPath(canonical.path)) return true
        val tags = directoryTags(canonical.connectionId, canonical.path)
        if (tags.isEmpty()) return false
        return tags.any(::isMangaTag) ||
            aliasCanonicals(canonical.connectionId, tags).any(::isMangaTag)
    }
}
