package com.openminis.app.share

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Reader for the archives [ChatExporter] writes — the inverse of the export.
 *
 * [ChatExporter.exportToZip] produces a zip holding two entries:
 *   - `messages.json` — a JSON array of `{id, role, content, created_at}`
 *     where `content` is the message's `parts_json` (a JSON *string* holding
 *     the parts array), or `messages.txt` when the user picked the plain-text
 *     format;
 *   - `session.json`   — title / model_id / timestamps / counts.
 *
 * This object deliberately knows nothing about Android, Room or the UI: it
 * takes a plain [File] and hands back plain data, so the whole parse path —
 * zip layout, both transcript formats, escaping, truncation — is exercised by
 * JVM unit tests ([ChatImportParserTest]). [ChatImporter] is the Android-side
 * half (Uri → staged file → this parser → rows).
 *
 * Memory: messages are streamed one at a time. The export was rewritten to
 * stream for exactly this reason (a 3k-message chat must not become a 3k-object
 * JSONArray in memory on *either* side), so the reader keeps that promise with
 * [JSONTokener.nextValue], which pulls one top-level value at a time off the
 * reader instead of materializing the array.
 *
 * Accepted inputs, in order of fidelity:
 *   1. a `.zip` from this app's export (json or text transcript) — full
 *      fidelity, including session metadata;
 *   2. a bare `messages.json` (the array as it sits inside the zip);
 *   3. a bare `.txt` transcript in the exported `You: … / Assistant: …` shape;
 *   4. a bare JSON object `{"session": {…}, "messages": […]}` (hand-made or
 *      third-party files). Only objects up to [MAX_OBJECT_BYTES] are accepted —
 *      that shape cannot be streamed because the messages array is a nested
 *      member, so the size gate is what keeps the "no unbounded allocation"
 *      property honest.
 */
object ChatImportParser {

    const val ENTRY_MESSAGES_JSON = "messages.json"
    const val ENTRY_MESSAGES_TXT = "messages.txt"
    const val ENTRY_SESSION = "session.json"

    /**
     * Ceiling for the `{session, messages}` object shape, which must be parsed
     * whole (see class doc). 8 MiB is far above a hand-made file and far below
     * anything that would threaten the heap.
     */
    const val MAX_OBJECT_BYTES = 8L * 1024 * 1024

    /** Roles we store verbatim; anything else is coerced to `user`. */
    private val KNOWN_ROLES = setOf("user", "assistant", "system", "tool")

    /**
     * `You: text` / `Assistant: text` — the two labels [ChatExporter] writes,
     * plus the wording other tools commonly use, with optional `**` emphasis
     * (models and other exporters love wrapping the speaker in bold).
     */
    private val ROLE_MARKER = Regex(
        "^\\s*(?:\\*\\*|__)?(you|user|me|assistant)(?:\\*\\*|__)?\\s*:\\s?(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** Value part types that carry a file payload the archive does NOT hold. */
    private val MEDIA_TYPES = setOf("mediaref", "image", "image_url", "video", "video_url", "file")

    // ─── Format detection ──────────────────────────────────────────────────

    fun detectFormat(file: File): ImportFormat {
        if (!file.isFile) throw ChatImportException("Import file is missing")
        if (file.length() == 0L) throw ChatImportException("Import file is empty")
        if (looksLikeZip(file)) {
            val names = zipEntryNames(file)
            return when {
                names.any { it.endsWith(ENTRY_MESSAGES_JSON) } -> ImportFormat.ZIP_JSON
                names.any { it.endsWith(ENTRY_MESSAGES_TXT) } -> ImportFormat.ZIP_TEXT
                else -> throw ChatImportException(
                    "Archive holds no $ENTRY_MESSAGES_JSON / $ENTRY_MESSAGES_TXT",
                )
            }
        }
        return when (val first = firstSignificantChar(file)) {
            '[' , '{' -> ImportFormat.JSON
            null -> throw ChatImportException("Import file is empty")
            else -> ImportFormat.TEXT
        }
    }

    // ─── Header ────────────────────────────────────────────────────────────

    fun readHeader(file: File, format: ImportFormat): ImportedHeader = when (format) {
        ImportFormat.ZIP_JSON, ImportFormat.ZIP_TEXT -> readZipHeader(file)
        ImportFormat.JSON -> objectShape(file)?.let { headerFrom(it) } ?: ImportedHeader()
        ImportFormat.TEXT -> ImportedHeader(title = textTranscriptTitle(file))
    }

    private fun readZipHeader(file: File): ImportedHeader {
        val entryStream = openEntry(file, ENTRY_SESSION) ?: return ImportedHeader()
        return entryStream.use { stream ->
            val text = stream.readBytes().toString(StandardCharsets.UTF_8)
            if (text.isBlank()) return@use ImportedHeader()
            try {
                headerFrom(JSONObject(text))
            } catch (_: Throwable) {
                // A corrupt sidecar must not sink a perfectly good transcript —
                // the messages are the payload; the header is a convenience.
                ImportedHeader()
            }
        }
    }

    private fun headerFrom(obj: JSONObject): ImportedHeader {
        // Tolerate the two nestings we have seen in the wild: flat (this app's
        // `session.json`) and `{"session": {…}}` (hand-made / iOS-shaped).
        val s = obj.optJSONObject("session") ?: obj
        return ImportedHeader(
            title = s.optString("title").takeIf { it.isNotBlank() },
            modelId = s.optString("model_id")
                .ifBlank { s.optString("modelId") }
                .takeIf { it.isNotBlank() },
            category = s.optString("category").takeIf { it.isNotBlank() },
            createdAt = s.millis("created_at") ?: s.millis("createdAt")
                ?: s.millis("first_created_at"),
            updatedAt = s.millis("updated_at") ?: s.millis("updatedAt")
                ?: s.millis("last_created_at"),
        )
    }

    private fun JSONObject.millis(key: String): Long? {
        if (!has(key)) return null
        val v = optLong(key, -1L)
        return if (v > 0L) v else null
    }

    // ─── Messages ──────────────────────────────────────────────────────────

    /**
     * Stream every message in [file] through [block], returning how many were
     * seen. Throws [ChatImportException] on a malformed payload; a throw part
     * way through leaves the caller free to discard what it already wrote
     * (which is what [ChatImporter] does — the whole import runs against a
     * freshly minted session row).
     */
    suspend fun forEachMessage(
        file: File,
        format: ImportFormat,
        block: suspend (ImportedMessage) -> Unit,
    ): Int =
        when (format) {
            ImportFormat.ZIP_JSON -> {
                val stream = openEntry(file, ENTRY_MESSAGES_JSON)
                    ?: throw ChatImportException("Archive holds no $ENTRY_MESSAGES_JSON")
                // `use` is inline, so the suspending callback below is still
                // part of this coroutine (the stream stays open across it).
                stream.use { streamJsonArray(it.reader(), block) }
            }

            ImportFormat.ZIP_TEXT -> {
                val stream = openEntry(file, ENTRY_MESSAGES_TXT)
                    ?: throw ChatImportException("Archive holds no $ENTRY_MESSAGES_TXT")
                stream.use { streamTextTranscript(it.reader(), block) }
            }

            ImportFormat.JSON -> {
                val obj = objectShape(file)
                if (obj != null) {
                    val arr = obj.optJSONArray("messages")
                        ?: throw ChatImportException("JSON object holds no \"messages\" array")
                    var count = 0
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        block(messageFrom(o))
                        count++
                    }
                    count
                } else {
                    streamJsonArray(FileInputStream(file).reader(), block)
                }
            }

            ImportFormat.TEXT -> FileInputStream(file).reader().use { reader ->
                streamTextTranscript(reader, block)
            }
        }

    /**
     * Stream a top-level JSON array of message objects.
     *
     * The array is split by [JsonArrayObjectReader] and each element is handed
     * to [JSONObject] on its own, so peak memory is one message regardless of
     * transcript length. (`JSONArray(wholeText)` would put the whole transcript
     * on the heap — exactly what the export path was rewritten to avoid.)
     *
     * The split is hand-rolled because `org.json` on Android cannot stream:
     * `android.jar`'s [org.json.JSONTokener] only has the `String` constructor,
     * while the reference implementation (the jar the unit tests run against)
     * also offers `(Reader)`. An earlier draft used the reader-based tokener and
     * compiled locally but not on the Android build — see the class doc's note
     * about tests not being able to cover the platform's own JSON API.
     */
    private suspend fun streamJsonArray(
        reader: Reader,
        block: suspend (ImportedMessage) -> Unit,
    ): Int = try {
        JsonArrayObjectReader(reader).use { elements ->
            var seen = 0
            for (raw in elements) {
                // Non-object elements are skipped rather than fatal: only the
                // message shape is ours to judge, and a stray scalar in an
                // otherwise good transcript should not cost the whole chat.
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                block(messageFrom(obj))
                seen++
            }
            seen
        }
    } catch (e: ChatImportException) {
        throw e
    } catch (e: Throwable) {
        throw ChatImportException("Could not parse the JSON transcript: ${e.message}")
    }

    /**
     * Parse the `You: … / Assistant: …` plain-text transcript
     * [ChatExporter] writes. A file with no markers at all is imported as a
     * single user message rather than rejected — a pasted transcript is still
     * a conversation worth keeping.
     */
    private suspend fun streamTextTranscript(
        reader: Reader,
        block: suspend (ImportedMessage) -> Unit,
    ): Int {
        val preamble = StringBuilder()
        val current = StringBuilder()
        var role: String? = null
        var count = 0

        // Local suspend function: it invokes the caller's (suspending)
        // callback, so it inherits the surrounding coroutine.
        suspend fun flush() {
            val text = current.toString().trim('\n', '\r', ' ', '\t')
            current.setLength(0)
            if (role != null && text.isNotEmpty()) {
                block(
                    ImportedMessage(
                        role = role!!,
                        partsJson = textPartsJson(text),
                        createdAt = null,
                    ),
                )
                count++
            }
        }

        // Plain read loop rather than `forEachLine { }`: the callback below
        // suspends, and a suspend call inside that inline lambda is rejected
        // ("suspension functions can only be called within coroutine body") —
        // the loop keeps the suspension in this function's own body.
        val buffered = BufferedReader(reader, 64 * 1024)
        while (true) {
            val line = buffered.readLine() ?: break
            val match = ROLE_MARKER.find(line)
            if (match != null) {
                flush()
                role = if (match.groupValues[1].equals("assistant", ignoreCase = true)) {
                    "assistant"
                } else {
                    "user"
                }
                current.append(match.groupValues[2])
            } else if (role == null) {
                // Everything before the first marker is the transcript's
                // heading — the exporter writes the session title there.
                if (preamble.isNotEmpty()) preamble.append('\n')
                preamble.append(line)
            } else {
                current.append('\n').append(line)
            }
        }
        flush()

        if (count == 0) {
            val whole = preamble.append(if (preamble.isEmpty()) "" else "\n")
                .append(current)
                .toString()
                .trim()
            if (whole.isNotEmpty()) {
                block(ImportedMessage(role = "user", partsJson = textPartsJson(whole)))
                count = 1
            }
        }
        return count
    }

    private fun textTranscriptTitle(file: File): String? = try {
        FileInputStream(file).reader().use { reader ->
            BufferedReader(reader).useLines { lines ->
                lines.take(20)
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && ROLE_MARKER.find(it) == null }
            }
        }
    } catch (_: Throwable) {
        null
    }

    // ─── Message shape ─────────────────────────────────────────────────────

    private fun messageFrom(o: JSONObject): ImportedMessage {
        val rawRole = o.optString("role").trim().lowercase()
        val role = if (rawRole in KNOWN_ROLES) rawRole else "user"
        val partsJson = normalizeParts(o.optString("content", ""))
        return ImportedMessage(
            role = role,
            partsJson = partsJson,
            createdAt = o.millis("created_at") ?: o.millis("createdAt")
                ?: o.millis("timestamp"),
            reasoning = o.optString("reasoning_content")
                .ifBlank { o.optString("reasoningContent") }
                .takeIf { it.isNotBlank() && it != "null" },
            mediaRefs = countMediaRefs(partsJson),
        )
    }

    /**
     * A message's `content` is the exported `parts_json` — a JSON array in a
     * string. Anything that is not a parseable array (a plain string from a
     * third-party file, a truncated part, `""`) is wrapped as a single text
     * part instead, so no message is ever dropped for shape reasons alone.
     */
    internal fun normalizeParts(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            try {
                JSONArray(trimmed)
                return trimmed
            } catch (_: Throwable) {
                // fall through to the text wrap below
            }
        }
        return textPartsJson(raw)
    }

    internal fun textPartsJson(text: String): String =
        JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString()

    internal fun countMediaRefs(partsJson: String): Int = try {
        val arr = JSONArray(partsJson)
        var n = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type").lowercase() in MEDIA_TYPES) n++
        }
        n
    } catch (_: Throwable) {
        0
    }

    // ─── File helpers ──────────────────────────────────────────────────────

    private fun looksLikeZip(file: File): Boolean {
        val head = ByteArray(4)
        val read = FileInputStream(file).use { it.read(head) }
        if (read < 4) return false
        return head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() ||
                head[2] == 0x07.toByte())
    }

    private fun zipEntryNames(file: File): List<String> = try {
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            val names = mutableListOf<String>()
            var entry = zip.nextEntry
            while (entry != null && names.size < 512) {
                names.add(entry.name)
                entry = zip.nextEntry
            }
            names
        }
    } catch (e: Throwable) {
        throw ChatImportException("Could not read the archive: ${e.message}")
    }

    /**
     * Open the first entry whose name ends with [name] — exports written by
     * this app sit at the archive root, but a re-zipped copy usually nests
     * them under a folder, and both should import.
     */
    private fun openEntry(file: File, name: String): InputStream? {
        val zip = ZipInputStream(FileInputStream(file).buffered())
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(name)) return zip
            entry = zip.nextEntry
        }
        zip.close()
        return null
    }

    private fun firstSignificantChar(file: File): Char? {
        FileInputStream(file).reader().use { reader ->
            var c = reader.read()
            while (c >= 0) {
                val ch = c.toChar()
                // Skip a UTF-8 BOM (a Windows editor will happily prepend one).
                if (!ch.isWhitespace() && ch != '\uFEFF') return ch
                c = reader.read()
            }
        }
        return null
    }

    /** Parsed `{session, messages}` object shape, or null for the array shape. */
    private fun objectShape(file: File): JSONObject? {
        if (firstSignificantChar(file) != '{') return null
        if (file.length() > MAX_OBJECT_BYTES) {
            throw ChatImportException(
                "JSON object transcripts larger than ${MAX_OBJECT_BYTES / (1024 * 1024)} MB " +
                    "are not supported — import the exported .zip instead",
            )
        }
        val text = FileInputStream(file).use { it.readBytes().toString(StandardCharsets.UTF_8) }
        return try {
            JSONObject(text)
        } catch (e: Throwable) {
            throw ChatImportException("Could not parse the JSON transcript: ${e.message}")
        }
    }
}

/**
 * Incremental reader for a top-level JSON array: hands back one raw element at
 * a time, so the caller can parse (and store) each message without ever holding
 * the whole array.
 *
 * Written by hand because `org.json` on Android cannot stream — `JSONTokener`
 * there has only the `String` constructor, and `JSONArray` wants the entire
 * text. The scanner keeps just enough state to know whether it is inside a
 * string, which is what makes it safe against text containing `{`, `}`, `[`,
 * `]` or `,`: those characters only count when they are real JSON syntax, not
 * prose the model happened to write.
 *
 * Malformed input fails loudly ([ChatImportException]) rather than yielding a
 * partial conversation — [ChatImporter] rolls back what it has already written.
 */
private class JsonArrayObjectReader(
    readerIn: Reader,
) : Iterator<String>, java.io.Closeable {

    private val input = PushbackReader(BufferedReader(readerIn, 64 * 1024), 2)
    private var pending: String? = null
    private var started = false
    private var ended = false

    override fun hasNext(): Boolean {
        if (pending != null) return true
        if (ended) return false
        pending = readElement()
        if (pending == null) ended = true
        return pending != null
    }

    override fun next(): String {
        if (!hasNext()) throw NoSuchElementException("no more elements")
        val element = pending!!
        pending = null
        return element
    }

    override fun close() {
        input.close()
    }

    private fun readElement(): String? {
        if (!started) {
            val first = nextSignificant()
                ?: throw ChatImportException("The JSON transcript is empty")
            if (first != '[') throw ChatImportException("JSON payload is not an array of messages")
            started = true
        }
        var c = nextSignificant() ?: throw ChatImportException("The JSON array is not closed")
        // Tolerate repeated / trailing separators instead of failing on them.
        while (c == ',') {
            c = nextSignificant() ?: throw ChatImportException("The JSON array is not closed")
        }
        if (c == ']') return null
        return when (c) {
            '{', '[' -> readComposite(c)
            '"' -> readStringElement()
            else -> readScalar(c)
        }
    }

    /** Copy out one `{…}` / `[…]` value, honouring nesting and string literals. */
    private fun readComposite(first: Char): String {
        val buffer = StringBuilder()
        buffer.append(first)
        var depth = 1
        var inString = false
        var escaped = false
        while (depth > 0) {
            val c = read() ?: throw ChatImportException("JSON array ends mid-object")
            buffer.append(c)
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
        }
        return buffer.toString()
    }

    private fun readStringElement(): String {
        val buffer = StringBuilder("\"")
        var escaped = false
        while (true) {
            val c = read() ?: throw ChatImportException("JSON array ends mid-string")
            buffer.append(c)
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                break
            }
        }
        return buffer.toString()
    }

    /** A number / `true` / `false` / `null` element: read up to its separator. */
    private fun readScalar(first: Char): String {
        val buffer = StringBuilder()
        buffer.append(first)
        while (true) {
            val c = read() ?: break
            if (c == ',' || c == ']') {
                input.unread(c.code)
                break
            }
            buffer.append(c)
        }
        return buffer.toString().trim()
    }

    private fun nextSignificant(): Char? {
        while (true) {
            val c = read() ?: return null
            // Skip whitespace and a stray UTF-8 BOM.
            if (!c.isWhitespace() && c != '\uFEFF') return c
        }
    }

    private fun read(): Char? {
        val v = input.read()
        return if (v < 0) null else v.toChar()
    }
}
