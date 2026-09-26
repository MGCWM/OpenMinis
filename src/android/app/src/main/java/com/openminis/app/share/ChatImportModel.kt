package com.openminis.app.share

/**
 * Data carried out of an exported transcript by [ChatImportParser] — plain
 * values only, so the parser stays testable without Android (see the class doc
 * there) and [ChatImporter] keeps ownership of every Room/Context decision.
 */

/** `session.json` sidecar of an exported archive. Every field is optional. */
data class ImportedHeader(
    val title: String? = null,
    val modelId: String? = null,
    val category: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

/**
 * One message as it sits in the archive.
 *
 * [partsJson] is the stored `parts_json` — the same shape the app writes and
 * reads, so an imported message renders (and, if the user keeps chatting, is
 * replayed to the model) exactly like a native one.
 *
 * [createdAt] is null when the source had no timestamp (plain-text
 * transcripts); [ChatImporter] then keeps the archive's order by spacing the
 * rows one second apart from the session's start.
 *
 * [mediaRefs] counts parts that point at a file on disk. The archive does NOT
 * carry those payloads (see ChatExporter — it wraps the transcript and a
 * metadata sidecar, nothing else), so the count is reported to the user rather
 * than silently vanishing.
 */
data class ImportedMessage(
    val role: String,
    val partsJson: String,
    val createdAt: Long? = null,
    val reasoning: String? = null,
    val mediaRefs: Int = 0,
)

/** What the picked file turned out to be. `label` is logged, never shown raw. */
enum class ImportFormat(val label: String) {
    ZIP_JSON("zip/json"),
    ZIP_TEXT("zip/text"),
    JSON("json"),
    TEXT("text"),
    ;

    val isZip: Boolean get() = this == ZIP_JSON || this == ZIP_TEXT
}

/**
 * A payload we cannot import, with a message fit for a Toast. Thrown by the
 * parser *and* the importer, so the UI has one type to catch.
 */
class ChatImportException(message: String) : Exception(message)
