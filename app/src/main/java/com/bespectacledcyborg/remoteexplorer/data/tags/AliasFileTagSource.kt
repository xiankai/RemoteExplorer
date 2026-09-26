package com.bespectacledcyborg.remoteexplorer.data.tags

import LocalDatabaseDataSource
import android.util.Log
import com.bespectacledcyborg.remoteexplorer.data.db.TagAlias
import kotlinx.serialization.Serializable
import java.io.BufferedReader

/**
 * Keeps the alias layer — which raw tags mean the same thing — in step with the share.
 *
 * The index is source-faithful: `眼鏡` from pixiv and `female:glasses` from examplebooru are
 * both stored as given, and match nothing of each other. The generator derives groups of
 * them into `aliases.ndjson`, one group per line:
 *
 * ```
 * {"canonical":"female/glasses","members":["glasses","female:glasses","眼鏡"],…}
 * ```
 *
 * and a tag lookup widens to every member of the tag's group at query time. They are kept
 * apart rather than expanded into relations because the mapping is the part that changes
 * most — curated, heuristic, often corrected — and a correction should be a small download,
 * not a re-ingest of every gallery.
 *
 * **Its own generation, and nothing like the index's protocol.** The file is small and
 * rewritten whole on every build, and the generation bumps on every build, so there is no
 * offset to resume from: a different generation means take the whole file and replace what
 * this connection had. Run as a source of its own rather than inside [IndexFileTagSource] so
 * that it cannot be gated on the index having changed — the index rebuilds once a day, the
 * aliases whenever someone makes a decision in the editor.
 *
 * `canonical` is an opaque group key. It is often not a tag at all (`?/1girl` is a group
 * whose root nobody has chosen yet), so it is stored to join on and never shown.
 */
class AliasFileTagSource(
    private val files: TagIndexFiles,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
) : TagSource {

    // Adds no tags, so it can reach nothing — false is the neutral answer under
    // CombinedTagSource's any().
    override val capabilities = TagSourceCapabilities(
        indexesUnvisitedDirectories = false,
        supportsWrites = false,
    )

    /**
     * Replaces this connection's aliases if the share has a generation it has not seen.
     *
     * [limit] does not apply — the file is one unit, and tens of kilobytes. [reindexAll]
     * takes it again regardless of generation.
     */
    override suspend fun index(
        connectionId: Int,
        reindexAll: Boolean,
        limit: Int,
    ): TagIndexProgress {
        // Read before the body, and it is this generation that gets stored. The generator
        // writes the body first and the meta after, so a build landing between the two reads
        // leaves newer content under an older generation — which the next pass sees as a
        // change and re-pulls. The other order would store a generation against content
        // older than it, and nothing would notice until the build after.
        val meta = readMeta(connectionId) ?: return TagIndexProgress.NOTHING

        if (!reindexAll &&
            localDatabaseDataSource.getTagAliasGeneration(connectionId) == meta.generation
        ) {
            return TagIndexProgress.NOTHING
        }

        val stream = files.openWhole(connectionId, TagIndex.ALIASES)
        if (stream == null) {
            // A meta with no file is a build caught half way, or a file deleted by hand.
            // Either way there is nothing to replace the current aliases with, and emptying
            // them would make every alias-widened lookup narrow again for no reason.
            Log.w(TAG, "${TagIndex.ALIASES_META} is there but ${TagIndex.ALIASES} is not")
            return TagIndexProgress.NOTHING
        }

        val groups = stream.use { raw ->
            BufferedReader(raw.reader()).lineSequence().mapNotNull(::parseTagAliasLine).toList()
        }
        val aliases = groups.flatMap { group ->
            group.members.map { TagAlias(connectionId, tag = it, canonical = group.canonical) }
        }

        localDatabaseDataSource.replaceTagAliases(connectionId, aliases, meta.generation)
        Log.i(TAG, "Aliases generation ${meta.generation}: ${groups.size} groups for $connectionId")
        return TagIndexProgress(groups.size, mayHaveMore = false)
    }

    private suspend fun readMeta(connectionId: Int): TagAliasMeta? {
        val bytes = files.readWhole(connectionId, TagIndex.ALIASES_META) ?: return null
        return runCatching {
            TagIndex.json.decodeFromString<TagAliasMeta>(bytes.decodeToString())
        }.getOrElse {
            // Same rule as the index's meta: unreadable is the same as absent.
            Log.w(TAG, "Could not read ${TagIndex.ALIASES_META}", it)
            null
        }
    }

    private companion object {
        const val TAG = "AliasFileTagSource"
    }
}

/**
 * The alias file's description of itself. Only the generation is used; the counts beside it
 * (`groups`, `queued`, `overrides`) are the editor's and are ignored.
 */
@Serializable
data class TagAliasMeta(
    val schema: Int,
    @Serializable(with = GenerationSerializer::class) val generation: String,
)

/** One line of `aliases.ndjson`: raw tags that name the same thing, under an opaque key. */
@Serializable
data class TagAliasGroup(
    val canonical: String,
    val members: List<String>,
)

/**
 * Parses one line, or returns null for one this build cannot use.
 *
 * Same rule as the index: a bad line costs that group, never the file. A group with fewer
 * than two members is dropped as well — it aliases nothing, and storing it would only add a
 * row to every lookup.
 */
fun parseTagAliasLine(line: String): TagAliasGroup? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val group = runCatching { TagIndex.json.decodeFromString<TagAliasGroup>(trimmed) }.getOrNull()
        ?: return null
    if (group.canonical.isEmpty()) return null
    val members = group.members.filter { it.isNotEmpty() }.distinct()
    if (members.size < 2) return null
    return group.copy(members = members)
}
