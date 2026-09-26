package com.bespectacledcyborg.remoteexplorer.data.tags

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The rolled-up tag index that gallery-dl writes to the share.
 *
 * Three files under `gallery-dl/_index/`, relative to the share root:
 *
 * ```
 * index.ndjson      the log, plain, range-readable
 * index.ndjson.gz   the same content, ~3.7x smaller, for a cold first pull
 * index.meta.json   {schema, generation, records, bytes, built_at}
 * ```
 *
 * and beside them the alias layer, which has a generation of its own — see [AliasFileTagSource].
 *
 * It exists so the app does not walk per-gallery sidecars over SMB, which was measured at
 * 20–40 minutes per 50k files at one round trip each. This is one transfer, and after the
 * first one it is a ranged read of whatever was appended since.
 *
 * **Absent or empty is not an error.** The generator ships before the backfill that fills
 * it, and a share that has never seen gallery-dl will never have it at all — which is the
 * ordinary case that [TagSource] already calls first-class.
 */
object TagIndex {
    const val DIRECTORY = "gallery-dl/_index"
    const val LOG = "$DIRECTORY/index.ndjson"
    const val COMPRESSED_LOG = "$DIRECTORY/index.ndjson.gz"
    const val META = "$DIRECTORY/index.meta.json"
    const val ALIASES = "$DIRECTORY/aliases.ndjson"
    const val ALIASES_META = "$DIRECTORY/aliases.meta.json"

    /**
     * Unknown keys are ignored rather than fatal, which is the whole forward-compatibility
     * story here: samplebooru records already carry a `token` this app has no use for, and the
     * generator is free to add more without every older build refusing to read the index.
     */
    val json = Json { ignoreUnknownKeys = true }
}

/**
 * What the index currently holds, read before deciding how much of it to fetch.
 *
 * [generation] changes when the generator rebuilds from scratch or changes schema, and is
 * the signal to throw away what was ingested and start again. [bytes] is the length the log
 * had when this was written, and so is both where to stop reading and the offset to resume
 * from next time.
 *
 * `built_at` is not read. The real generator writes it as an ISO timestamp, the fixture
 * used to write epoch seconds, and nothing here has a use for either — declaring it only
 * gave a field nobody reads the power to make the whole index unreadable.
 */
@Serializable
data class TagIndexMeta(
    val schema: Int,
    @Serializable(with = GenerationSerializer::class) val generation: String,
    val records: Int,
    val bytes: Long,
)

/**
 * A generation as a string, whether the file spells it as a number or a string.
 *
 * The real generator writes an integer (`"generation": 2`). The fixture wrote a string, this
 * was typed to match the fixture, and the result was that the index on the real share failed
 * to parse on every pass and read as "no index here" — silently, because an unreadable meta
 * is deliberately indistinguishable from none. Only equality is ever asked of it, so a string
 * holds either spelling without anyone having to agree which is right.
 */
internal object GenerationSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("TagIndexGeneration", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val element = decoder.decodeJsonElement()
        // JsonNull is itself a JsonPrimitive, so the second clause is what turns a null
        // generation into an error rather than the string "null". Tested.
        if (element !is JsonPrimitive || element is JsonNull) { // stack-reviewer-ignore: JsonNull extends JsonPrimitive, so this clause is reachable and is what rejects a null generation; see `rejects a null generation` in TagIndexParsingTest.
            throw SerializationException("generation is neither a number nor a string: $element")
        }
        return element.content
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** Whether a record's tags describe one file or everything directly inside a directory. */
enum class TagScope { DIRECTORY, FILE }

/**
 * One line of the log.
 *
 * The log is append-only and **the last record for a path wins** — a changed gallery is
 * appended rather than rewritten, which is exactly what makes resuming from a byte offset
 * valid. A record with [deleted] set is a tombstone: drop that path's tags.
 */
data class TagIndexRecord(
    val path: String,
    val scope: TagScope,
    val tags: List<String>,
    /**
     * How many files the directory held when the record was written, used for tag counts —
     * a directory-scoped tag has no per-file rows to count.
     *
     * Accurate as of the last tag change rather than as of now: the generator appends when
     * a gallery's tags change, so files added or removed without a tag change are not
     * reflected until the next one. That is a count in a list being slightly stale, which
     * nobody can see, and not worth a crawl to correct.
     */
    val files: Int,
    val deleted: Boolean,
)

/**
 * The wire form, kept separate from [TagIndexRecord] so the app's model does not have to
 * carry the generator's spelling — `scope` as a string, absent fields meaning defaults.
 */
@Serializable
private data class WireRecord(
    val path: String,
    val scope: String? = null,
    val tags: List<String>? = null,
    val files: Int? = null,
    val deleted: Boolean? = null,
)

/**
 * Parses one line, or returns null for a line this build cannot use.
 *
 * Null rather than throwing, for blank lines and for anything malformed or of a shape added
 * after this build: one unreadable record must not abort an ingest that is otherwise fine,
 * because the alternative is a single bad line on the share making every tag in the library
 * unreachable. A skipped record is a gallery without tags until the next full re-pull, which
 * is the same as it not being there yet.
 *
 * A record with no recognisable scope is skipped rather than guessed at. Guessing FILE for a
 * directory would attach a gallery's tags to nothing; guessing DIRECTORY for a file would
 * attach them to every sibling.
 */
fun parseTagIndexLine(line: String): TagIndexRecord? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    val wire = runCatching { TagIndex.json.decodeFromString<WireRecord>(trimmed) }.getOrNull()
        ?: return null
    if (wire.path.isEmpty()) return null

    val scope = when (wire.scope) {
        "dir" -> TagScope.DIRECTORY
        "file" -> TagScope.FILE
        else -> return null
    }

    // A tombstone carries no tags, and is the one record that is allowed to have none: an
    // empty tag list otherwise means the gallery has been un-tagged, which is also a
    // removal, so both arrive here as "this path has no tags" and are handled the same.
    return TagIndexRecord(
        path = wire.path,
        scope = scope,
        tags = wire.tags.orEmpty(),
        files = wire.files ?: 0,
        deleted = wire.deleted == true,
    )
}

/**
 * Splits a namespaced tag into its namespace and value, or null for a bare one.
 *
 * samplebooru and examplebooru emit `namespace:value` (`artist:foo`, `female:blush`); pixiv emits
 * bare tags, mostly Japanese. The colon is a safe delimiter because a value can contain
 * spaces but never a colon, so the first one splits losslessly.
 *
 * Values are deliberately not lowercased or normalised anywhere in this path. A raw tag
 * cannot be re-derived without going back to the provider, and examplebooru may never be
 * fetchable again, so normalisation belongs at query time against the alias layer — where a
 * correction costs a small download rather than a re-ingest of everything.
 */
fun splitNamespacedTag(tag: String): Pair<String, String>? {
    val colon = tag.indexOf(':')
    if (colon <= 0 || colon == tag.length - 1) return null
    return tag.substring(0, colon) to tag.substring(colon + 1)
}
