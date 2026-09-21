package com.openminis.app.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

data class PersonaPromptEntry(
    val id: String,
    val fileName: String,
    val storedName: String,
    val builtin: Boolean,
)

data class PersonaPromptIndex(
    val prompts: List<PersonaPromptEntry>,
    val selectedId: String,
    val providerSelections: Map<String, String>,
)

data class ResolvedPersonaPrompt(
    val id: String,
    val fileName: String,
    val body: String,
)

enum class PersonaImportError {
    EMPTY,
    TOO_LARGE,
    UNREADABLE,
    BAD_TYPE,
}

sealed class PersonaImportResult {
    data class Success(val entry: PersonaPromptEntry) : PersonaImportResult()
    data class Failure(val reason: PersonaImportError) : PersonaImportResult()
}

object PersonaPromptLogic {
    const val BUILTIN_ID = "soul"
    const val BUILTIN_FILE_NAME = "SOUL.md"
    const val FOLLOW_DEFAULT = ""
    const val MAX_IMPORT_BYTES = 1_048_576

    fun builtinEntry(): PersonaPromptEntry = PersonaPromptEntry(
        id = BUILTIN_ID,
        fileName = BUILTIN_FILE_NAME,
        storedName = "",
        builtin = true,
    )

    fun emptyIndex(): PersonaPromptIndex = PersonaPromptIndex(
        prompts = listOf(builtinEntry()),
        selectedId = BUILTIN_ID,
        providerSelections = emptyMap(),
    )

    fun isImportableName(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower.endsWith(".md") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".markdown")
    }

    fun isImportableMime(mime: String?): Boolean {
        val t = mime.orEmpty().lowercase()
        if (t.isEmpty() || t == "application/octet-stream") return false
        return t == "text/plain" ||
            t == "text/markdown" ||
            t == "text/x-markdown" ||
            t.startsWith("text/")
    }

    fun sanitizeFileName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = name.replace(Regex("[\\x00-\\x1F\\\\/:*?\"<>|]"), "_")
        if (name.isBlank() || name == "." || name == "..") name = "persona.md"
        return name.take(120)
    }

    fun uniqueDisplayName(desired: String, existing: Set<String>): String {
        if (desired !in existing) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate !in existing) return candidate
            n++
        }
    }

    fun extractImportedBody(raw: String): String {
        val text = raw.removePrefix("\uFEFF")
        return SoulMDParser.parse(text).body
    }

    fun normalizeIndex(index: PersonaPromptIndex): PersonaPromptIndex {
        val seen = linkedSetOf<String>()
        val prompts = mutableListOf<PersonaPromptEntry>()
        var hasBuiltin = false
        for (p in index.prompts) {
            if (p.id.isBlank() || p.id in seen) continue
            seen += p.id
            if (p.builtin || p.id == BUILTIN_ID) {
                hasBuiltin = true
                prompts += builtinEntry()
            } else if (p.storedName.isNotBlank() && p.fileName.isNotBlank()) {
                prompts += p
            }
        }
        if (!hasBuiltin) prompts.add(0, builtinEntry())
        val ids = prompts.map { it.id }.toSet()
        val selected = if (index.selectedId in ids) index.selectedId else BUILTIN_ID
        val providers = index.providerSelections.filter { (k, v) ->
            k.isNotBlank() && v in ids
        }
        return PersonaPromptIndex(prompts, selected, providers)
    }

    fun resolveId(index: PersonaPromptIndex, providerInstanceId: String?): String {
        val normalized = normalizeIndex(index)
        val ids = normalized.prompts.map { it.id }.toSet()
        val mapped = providerInstanceId
            ?.takeIf { it.isNotBlank() }
            ?.let { normalized.providerSelections[it] }
            ?.takeIf { it in ids }
        if (mapped != null) return mapped
        if (normalized.selectedId in ids) return normalized.selectedId
        return BUILTIN_ID
    }
}

object PersonaPromptCodec {
    fun parse(json: String): PersonaPromptIndex {
        if (json.isBlank()) return PersonaPromptLogic.emptyIndex()
        return try {
            val root = JSONObject(json)
            val prompts = mutableListOf<PersonaPromptEntry>()
            val arr = root.optJSONArray("prompts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                prompts += PersonaPromptEntry(
                    id = id,
                    fileName = o.optString("fileName").ifBlank { id },
                    storedName = o.optString("storedName"),
                    builtin = o.optBoolean("builtin") || id == PersonaPromptLogic.BUILTIN_ID,
                )
            }
            val providers = mutableMapOf<String, String>()
            val sel = root.optJSONObject("providerSelections")
            if (sel != null) {
                val keys = sel.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = sel.optString(k)
                    if (k.isNotBlank() && v.isNotBlank()) providers[k] = v
                }
            }
            PersonaPromptLogic.normalizeIndex(
                PersonaPromptIndex(
                    prompts = prompts,
                    selectedId = root.optString("selectedId"),
                    providerSelections = providers,
                ),
            )
        } catch (_: Exception) {
            PersonaPromptLogic.emptyIndex()
        }
    }

    fun serialize(index: PersonaPromptIndex): String {
        val normalized = PersonaPromptLogic.normalizeIndex(index)
        val arr = JSONArray()
        for (p in normalized.prompts) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("fileName", p.fileName)
                    .put("storedName", p.storedName)
                    .put("builtin", p.builtin),
            )
        }
        val providers = JSONObject()
        for ((k, v) in normalized.providerSelections) providers.put(k, v)
        return JSONObject()
            .put("prompts", arr)
            .put("selectedId", normalized.selectedId)
            .put("providerSelections", providers)
            .toString()
    }
}

/**
 * Imported personality prompts live under the app-private
 * `minis-global/memory/personas/` tree. The builtin [SOUL.md][SoulStore]
 * identity file stays where it is; imported `.md`/`.txt` copies never
 * leave [Context.getFilesDir].
 */
object PersonaPromptLibrary {
    private const val TAG = "PersonaPromptLibrary"
    private const val SUBDIR = "minis-global/memory/personas"
    private const val FILES_SUBDIR = "files"
    private const val INDEX_NAME = "index.json"

    private val lock = Any()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun root(context: Context): File = File(context.filesDir, SUBDIR)

    fun filesDir(context: Context): File = File(root(context), FILES_SUBDIR)

    fun indexFile(context: Context): File = File(root(context), INDEX_NAME)

    fun loadIndex(context: Context): PersonaPromptIndex = synchronized(lock) {
        loadIndexLocked(context)
    }

    fun setSelected(context: Context, promptId: String) {
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val ids = index.prompts.map { it.id }.toSet()
            val next = if (promptId in ids) promptId else PersonaPromptLogic.BUILTIN_ID
            persistLocked(context, index.copy(selectedId = next))
        }
    }

    fun setProviderPrompt(context: Context, providerInstanceId: String, promptId: String?) {
        if (providerInstanceId.isBlank()) return
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val nextMap = index.providerSelections.toMutableMap()
            val ids = index.prompts.map { it.id }.toSet()
            if (promptId.isNullOrBlank() || promptId == PersonaPromptLogic.FOLLOW_DEFAULT || promptId !in ids) {
                nextMap.remove(providerInstanceId)
            } else {
                nextMap[providerInstanceId] = promptId
            }
            persistLocked(context, index.copy(providerSelections = nextMap))
        }
    }

    fun readBody(context: Context, promptId: String): String = synchronized(lock) {
        readBodyLocked(context, promptId)
    }

    fun writeBody(context: Context, promptId: String, body: String) {
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val id = if (index.prompts.any { it.id == promptId }) {
                promptId
            } else {
                PersonaPromptLogic.BUILTIN_ID
            }
            if (id == PersonaPromptLogic.BUILTIN_ID) {
                val cur = SoulStore.load(context)
                    ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
                SoulStore.save(context, cur.copy(body = body))
            } else {
                val entry = index.prompts.first { it.id == id }
                val target = File(filesDir(context), entry.storedName)
                atomicWrite(target, body)
            }
            bumpLocked()
        }
    }

    fun resolve(context: Context, providerInstanceId: String?): ResolvedPersonaPrompt {
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val id = PersonaPromptLogic.resolveId(index, providerInstanceId)
            val entry = index.prompts.find { it.id == id } ?: PersonaPromptLogic.builtinEntry()
            return ResolvedPersonaPrompt(
                id = entry.id,
                fileName = entry.fileName,
                body = readBodyLocked(context, entry.id),
            )
        }
    }

    fun importFromUri(context: Context, uri: Uri): PersonaImportResult {
        val resolver = context.contentResolver
        val displayRaw = queryDisplayName(context, uri)
        val mime = resolver.getType(uri)
        val sanitized = PersonaPromptLogic.sanitizeFileName(
            displayRaw.ifBlank {
                when {
                    mime == "text/markdown" || mime == "text/x-markdown" -> "persona.md"
                    else -> "persona.txt"
                }
            },
        )
        if (!PersonaPromptLogic.isImportableName(sanitized) &&
            !PersonaPromptLogic.isImportableMime(mime)
        ) {
            return PersonaImportResult.Failure(PersonaImportError.BAD_TYPE)
        }

        val stream = try {
            resolver.openInputStream(uri)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import open failed: ${t.message}")
            return PersonaImportResult.Failure(PersonaImportError.UNREADABLE)
        }
        if (stream == null) {
            return PersonaImportResult.Failure(PersonaImportError.UNREADABLE)
        }
        val bytes = try {
            stream.use { readLimited(it, PersonaPromptLogic.MAX_IMPORT_BYTES) }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import read failed: ${t.message}")
            return PersonaImportResult.Failure(PersonaImportError.UNREADABLE)
        }
        if (bytes == null) {
            return PersonaImportResult.Failure(PersonaImportError.TOO_LARGE)
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import decode failed: ${t.message}")
            return PersonaImportResult.Failure(PersonaImportError.UNREADABLE)
        }
        val body = PersonaPromptLogic.extractImportedBody(text)
        if (body.isBlank()) {
            return PersonaImportResult.Failure(PersonaImportError.EMPTY)
        }

        synchronized(lock) {
            val index = loadIndexLocked(context)
            val existingNames = index.prompts.map { it.fileName }.toSet()
            val fileName = PersonaPromptLogic.uniqueDisplayName(
                if (PersonaPromptLogic.isImportableName(sanitized)) sanitized else "$sanitized.md",
                existingNames,
            )
            val id = UUID.randomUUID().toString()
            val storedName = "$id.md"
            val dest = File(filesDir(context), storedName)
            atomicWrite(dest, body)
            val entry = PersonaPromptEntry(
                id = id,
                fileName = fileName,
                storedName = storedName,
                builtin = false,
            )
            persistLocked(
                context,
                index.copy(
                    prompts = index.prompts + entry,
                    selectedId = id,
                ),
            )
            return PersonaImportResult.Success(entry)
        }
    }

    fun deleteImported(context: Context, promptId: String): Boolean {
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val entry = index.prompts.find { it.id == promptId } ?: return false
            if (entry.builtin) return false
            val dest = File(filesDir(context), entry.storedName)
            if (dest.exists() && !dest.delete()) {
                AppLogger.warning(TAG, "failed to delete ${entry.storedName}")
            }
            val remaining = index.prompts.filterNot { it.id == promptId }
            val selected = if (index.selectedId == promptId) {
                PersonaPromptLogic.BUILTIN_ID
            } else {
                index.selectedId
            }
            val providers = index.providerSelections.filterValues { it != promptId }
            persistLocked(
                context,
                PersonaPromptIndex(remaining, selected, providers),
            )
            return true
        }
    }

    private fun loadIndexLocked(context: Context): PersonaPromptIndex {
        val file = indexFile(context)
        if (!file.isFile) {
            return PersonaPromptLogic.emptyIndex()
        }
        return try {
            PersonaPromptCodec.parse(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "index load failed: ${t.message}")
            PersonaPromptLogic.emptyIndex()
        }
    }

    private fun persistLocked(context: Context, index: PersonaPromptIndex) {
        val normalized = PersonaPromptLogic.normalizeIndex(index)
        atomicWrite(indexFile(context), PersonaPromptCodec.serialize(normalized))
        bumpLocked()
    }

    private fun bumpLocked() {
        _revision.value = _revision.value + 1
    }

    private fun readBodyLocked(context: Context, promptId: String): String {
        if (promptId == PersonaPromptLogic.BUILTIN_ID) {
            return SoulStore.load(context)?.body.orEmpty()
        }
        val index = loadIndexLocked(context)
        val entry = index.prompts.find { it.id == promptId }
        if (entry == null || entry.builtin) {
            return SoulStore.load(context)?.body.orEmpty()
        }
        val dest = File(filesDir(context), entry.storedName)
        if (!dest.isFile) return SoulStore.load(context)?.body.orEmpty()
        return try {
            dest.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "body read failed: ${t.message}")
            SoulStore.load(context)?.body.orEmpty()
        }
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun readLimited(stream: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx).orEmpty()
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment.orEmpty()
    }
}
