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
    const val ANNOTATIONS = "$DIRECTORY/annotations.ndjson"
    const val ANNOTATIONS_META = "$DIRECTORY/annotations.meta.json"

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

data class TagIndexRecord(
    val path: String,
    val scope: TagScope,
    val tags: List<String>,
    val files: Int,
    val deleted: Boolean,
    val annotations: List<String> = emptyList(),
)

@Serializable
private data class WireRecord(
    val path: String,
    val scope: String? = null,
    val tags: List<String>? = null,
    val files: Int? = null,
    val deleted: Boolean? = null,
    val annotations: List<String>? = null,
)

fun parseTagIndexLine(line: String): TagIndexRecord? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    val wire = runCatching { TagIndex.json.decodeFromString<WireRecord>(trimmed) }.getOrNull()
        ?: return null
    if (wire.path.isEmpty()) return null

    val scope = parseScope(wire.scope) ?: return null

    return TagIndexRecord(
        path = wire.path,
        scope = scope,
        tags = wire.tags.orEmpty(),
        files = wire.files ?: 0,
        deleted = wire.deleted == true,
        annotations = wire.annotations.orEmpty(),
    )
}

internal fun parseScope(scope: String?): TagScope? = when (scope) {
    "dir" -> TagScope.DIRECTORY
    "file" -> TagScope.FILE
    else -> null
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
