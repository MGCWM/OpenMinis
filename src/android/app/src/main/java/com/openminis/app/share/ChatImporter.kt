package com.openminis.app.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Import a conversation exported by [ChatExporter] — the missing half of a
 * long-press → Export. Export has existed since T-export-optimize; until now
 * there was no way back in, so a chat moved between devices (or kept as a
 * backup before a wipe) could only be read as a zip in a file manager.
 *
 * Shape of the job:
 *   1. copy the picked [Uri] into `cacheDir/import-staging/<uuid>/` — one
 *      seekable file, because the archive is read twice (header sidecar, then
 *      the transcript) and a content Uri cannot be rewound in general;
 *   2. [ChatImportParser] decides what the file is and streams the header +
 *      every message (no whole-transcript buffer on either end);
 *   3. insert ONE new session row, then insert messages in batches of
 *      [BATCH_SIZE] against it, preserving the archive's order and its
 *      timestamps;
 *   4. stamp the session's `last_message` preview from the last message that
 *      yields one, exactly as [ChatRepository.appendMessage] would, so the row
 *      looks native in the session list.
 *
 * Deliberate choices, each of which a reviewer will otherwise ask about:
 *
 *   - **New ids everywhere.** Message and session ids are minted locally. The
 *     archive's ids are *not* reused: importing twice would otherwise collide
 *     (REPLACE silently merging two conversations), and a chat exported from
 *     this device still exists here under its original id.
 *   - **`updated_at` = now, `created_at` = the archive's.** Importing a chat
 *     from last year should not require scrolling to last year to find it, so
 *     the row lands at the top of the list; the *messages* keep their true
 *     timestamps, which is where fidelity actually matters (the transcript
 *     reads with the right dates). The header's `updated_at` is used only as a
 *     createdAt fallback.
 *   - **The model id is re-resolved.** A model id that does not exist in this
 *     install (other device, deleted provider, relay-prefixed id) would leave
 *     the chat unable to send anything. The header's id is kept when the model
 *     is present; otherwise it falls back to the user's first visible entry and
 *     [Result.modelRemapped] tells the UI to say so.
 *   - **Attachment payloads are not in the archive.** The exporter wraps the
 *     transcript + a metadata sidecar and nothing else, so `mediaRef` parts
 *     point at files this device does not have. They are left in place
 *     verbatim (so a future exporter that DOES ship media needs no change
 *     here) and counted into [Result.mediaRefCount] for the user to be told.
 *   - **One bad file never leaves debris.** Everything is written under a
 *     freshly minted session id, and any failure (or cancellation) deletes
 *     that session — the message rows cascade with it.
 *
 * Progress is published on [importedCount] for the progress dialog; it counts
 * messages, which is the only number that moves.
 */
object ChatImporter {

    private const val TAG = "ChatImporter"
    private const val BATCH_SIZE = 50
    private const val MAX_TITLE_LENGTH = 120

    private val _importedCount = MutableStateFlow(0)

    /** Messages read so far in the current import. Reset at the start of each. */
    val importedCount: StateFlow<Int> = _importedCount.asStateFlow()

    /** What an import actually produced, for the confirmation dialog. */
    data class Result(
        val sessionId: String,
        val title: String,
        val messageCount: Int,
        /** Parts pointing at local files the archive does not carry. */
        val mediaRefCount: Int,
        /** Model the imported chat is bound to — see the class doc. */
        val modelId: String,
        /** Model named by the archive, null when it carried none. */
        val originalModelId: String?,
        /** True when [modelId] is a local fallback rather than the archive's. */
        val modelRemapped: Boolean,
        val formatLabel: String,
    )

    /**
     * Read [uri] and store it as a new conversation. Throws
     * [ChatImportException] with a user-facing message on any failure.
     */
    suspend fun importChat(
        context: Context,
        uri: Uri,
        repository: ChatRepository,
        providerRepository: ProviderRepository,
        fallbackTitle: String,
    ): Result = withContext(Dispatchers.IO) {
        _importedCount.value = 0
        val workDir = File(File(context.cacheDir, "import-staging"), UUID.randomUUID().toString())
        if (!workDir.mkdirs() && !workDir.isDirectory) {
            throw ChatImportException("Could not create a staging directory")
        }
        val staged = File(workDir, "import.bin")
        var sessionId: String? = null
        try {
            copyToFile(context, uri, staged)

            val format = ChatImportParser.detectFormat(staged)
            val header = ChatImportParser.readHeader(staged, format)
            val title = resolveTitle(header, displayNameOf(context, uri), fallbackTitle)
            val resolved = resolveModel(header.modelId, providerRepository)

            val now = System.currentTimeMillis()
            sessionId = UUID.randomUUID().toString()
            val sessionCreatedAt = header.createdAt ?: header.updatedAt ?: now
            repository.dao.insertSession(
                ChatSessionEntity(
                    id = sessionId,
                    title = title,
                    modelId = resolved.modelId,
                    createdAt = sessionCreatedAt,
                    // See the class doc: "just imported" beats "filed under
                    // its original date" for findability.
                    updatedAt = now,
                    category = header.category,
                    // Provenance tag, same field the shortcut/share entries
                    // use — surfaces in minis-sessions-cli list.
                    source = "import",
                    // New sessions follow the global memory default, exactly
                    // like ChatViewModel.ensureSession's createSession call.
                    memoryEnabled = if (MemoryGlobalPrefs.isGlobalEnabled(context)) 1 else 0,
                    folderId = null,
                ),
            )

            var lastTs = sessionCreatedAt
            var lastPreview: String? = null
            var mediaRefs = 0
            var written = 0
            val batch = ArrayList<MessageEntity>(BATCH_SIZE)

            // Local suspend function: the parser's callback is a suspend
            // lambda, so each batch can be awaited before the next message is
            // read — back pressure for free (and the reason the callback type
            // is `suspend` rather than a plain function type).
            suspend fun drain() {
                if (batch.isEmpty()) return
                repository.dao.insertMessages(ArrayList(batch))
                batch.clear()
            }

            ChatImportParser.forEachMessage(staged, format) { msg ->
                val ts = if (msg.createdAt != null && msg.createdAt > 0) {
                    lastTs = msg.createdAt
                    msg.createdAt
                } else {
                    // Timestamp-less sources (plain-text transcripts) keep
                    // their order by advancing one second per message from
                    // wherever the previous one landed.
                    lastTs += 1000L
                    lastTs
                }
                val capped = if (msg.partsJson.length > ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH) {
                    ChatRepository.buildTruncatedPartsJson(msg.partsJson)
                } else {
                    msg.partsJson
                }
                mediaRefs += msg.mediaRefs
                ChatRepository.extractTextPreview(capped)?.let { lastPreview = it }
                batch.add(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId!!,
                        role = msg.role,
                        partsJson = capped,
                        createdAt = ts,
                        tokenUsage = null,
                        sortOrder = written,
                        reasoningContent = msg.reasoning,
                        updatedAt = ts,
                        // Attribution columns stay null: an imported turn was
                        // not served by any model on THIS device, and null is
                        // exactly how the Usage page says "estimated from the
                        // session" instead of inventing a measurement.
                    ),
                )
                written++
                if (batch.size >= BATCH_SIZE) {
                    drain()
                    _importedCount.value = written
                }
            }
            drain()
            _importedCount.value = written

            if (written == 0) {
                throw ChatImportException("The file holds no messages")
            }

            // Mirror appendMessage's session bookkeeping: preview when the
            // last message yields one, otherwise just keep the row recent.
            if (lastPreview != null) {
                repository.dao.updateLastMessage(sessionId!!, lastPreview, now)
            } else {
                repository.dao.touchSession(sessionId!!, now)
            }

            AppLogger.info(
                TAG,
                "imported $written message(s) from ${format.label} into ${sessionId!!.take(8)} " +
                    "(title=${title.take(24)}, model=${resolved.modelId}" +
                    (if (resolved.remapped) " [fallback for ${resolved.original}]" else "") +
                    ", mediaRefs=$mediaRefs)",
            )

            Result(
                sessionId = sessionId!!,
                title = title,
                messageCount = written,
                mediaRefCount = mediaRefs,
                modelId = resolved.modelId,
                originalModelId = resolved.original,
                modelRemapped = resolved.remapped,
                formatLabel = format.label,
            )
        } catch (t: Throwable) {
            // Roll back the half-written conversation. The message rows hang
            // off the session by foreign key, so one delete takes them too.
            sessionId?.let { id ->
                runCatching { repository.deleteSession(id) }
                    .onFailure { AppLogger.warning(TAG, "rollback of $id failed: ${it.message}") }
            }
            AppLogger.error(TAG, "import failed: ${t::class.java.simpleName}: ${t.message}")
            if (t is CancellationException) throw t
            throw if (t is ChatImportException) t else {
                ChatImportException(t.message ?: "The file could not be imported")
            }
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private data class ResolvedModel(
        val modelId: String,
        val original: String?,
        val remapped: Boolean,
    )

    private fun resolveModel(
        headerModelId: String?,
        providerRepository: ProviderRepository,
    ): ResolvedModel {
        val entries = runCatching { providerRepository.allVisibleEntries() }.getOrElse { emptyList() }
        val available = entries.map { it.model.id }
        if (headerModelId != null && headerModelId in available) {
            return ResolvedModel(headerModelId, headerModelId, remapped = false)
        }
        // The archive's model is unknown here (other device / removed provider
        // / relay id) — bind to what this install can actually call. Same
        // fallback ChatViewModel.ensureSession uses for a brand-new chat.
        val fallback = available.firstOrNull() ?: ""
        return ResolvedModel(fallback, headerModelId, remapped = headerModelId != null)
    }

    private fun resolveTitle(
        header: ImportedHeader,
        fileName: String?,
        fallbackTitle: String,
    ): String {
        val fromHeader = header.title?.trim()?.takeIf { it.isNotEmpty() }
        val fromFile = fileName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return (fromHeader ?: fromFile ?: fallbackTitle).take(MAX_TITLE_LENGTH)
    }

    private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun copyToFile(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ChatImportException("The file could not be opened")
        input.use { source ->
            target.outputStream().buffered().use { sink -> source.copyTo(sink, 64 * 1024) }
        }
        if (target.length() == 0L) throw ChatImportException("The file is empty")
    }
}
