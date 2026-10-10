package com.bespectacledcyborg.remoteexplorer.data.tags

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val NARRATION = "narration"

data class AnnotationRecord(
    val path: String,
    val scope: TagScope,
    val id: String,
    val text: String?,
    val language: String?,
    val derivedFrom: String?,
    val supersedes: String?,
) {
    val isLive: Boolean get() = text != null
}

@Serializable
private data class WireAnnotation(
    val path: String,
    val scope: String? = null,
    val id: String,
    val kind: String? = null,
    val quality: String? = null,
    val deleted: Boolean? = null,
    val body: WireBody? = null,
    @SerialName("derived_from") val derivedFrom: WireReference? = null,
    val supersedes: String? = null,
)

@Serializable
private data class WireBody(
    val value: String? = null,
    val language: String? = null,
)

@Serializable
private data class WireReference(
    val path: String? = null,
    val id: String? = null,
)

fun parseAnnotationLine(line: String): AnnotationRecord? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    val wire = runCatching { TagIndex.json.decodeFromString<WireAnnotation>(trimmed) }.getOrNull()
        ?: return null
    if (wire.path.isEmpty() || wire.id.isEmpty()) return null
    val scope = parseScope(wire.scope) ?: return null

    val shown = wire.deleted != true &&
            (wire.kind ?: NARRATION) == NARRATION &&
            wire.quality == NARRATION &&
            !wire.body?.value.isNullOrBlank()

    return AnnotationRecord(
        path = wire.path,
        scope = scope,
        id = wire.id,
        text = if (shown) wire.body?.value else null,
        language = wire.body?.language?.takeIf { it.isNotBlank() },
        derivedFrom = wire.derivedFrom?.id,
        supersedes = wire.supersedes,
    )
}
