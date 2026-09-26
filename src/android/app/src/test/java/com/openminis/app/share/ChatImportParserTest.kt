package com.openminis.app.share

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [T-android-chat-import] The read half of long-press → Export.
 *
 * These tests drive [ChatImportParser] on the file shapes it has to accept —
 * the zip this app writes (json and text transcript), a bare `messages.json`,
 * a bare `.txt`, and the `{session, messages}` object shape — plus the shapes
 * it has to reject. They are the reason the parser takes a [File] and returns
 * plain data: everything here runs on the JVM, with no Room, no Context and no
 * device (the one thing that cannot be proven here is the DB write, which is
 * [ChatImporter]'s half and is exercised by hand on install).
 *
 * The fixtures are built the way [ChatExporter] writes them, field for field
 * (`id`/`role`/`content`/`created_at` where `content` is the parts_json
 * *string*, plus the `session.json` sidecar) — if the exporter's shape ever
 * changes, these tests fail, which is exactly the coupling that should be
 * asserted somewhere.
 */
class ChatImportParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ─── Fixtures ──────────────────────────────────────────────────────────

    private fun parts(vararg parts: JSONObject): String = JSONArray().putAll(parts).toString()

    private fun textPart(value: String): JSONObject =
        JSONObject().put("type", "text").put("value", value)

    private fun messageJson(
        id: String,
        role: String,
        partsJson: String,
        createdAt: Long? = null,
        reasoning: String? = null,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role)
        put("content", partsJson)
        if (createdAt != null) put("created_at", createdAt)
        if (reasoning != null) put("reasoning_content", reasoning)
    }

    private fun write(name: String, body: String): File =
        temp.newFile(name).apply { writeText(body, Charsets.UTF_8) }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File(temp.newFolder(), "export.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun exportHeader(
        title: String = "Trip plan",
        modelId: String = "cn:deepseek-v4.1-flash",
        createdAt: Long = 1_700_000_000_000L,
    ): String = JSONObject().apply {
        put("id", "session-1")
        put("title", title)
        put("model_id", modelId)
        put("created_at", createdAt)
        put("updated_at", createdAt + 60_000)
        put("message_count", 3)
        put("format", "json")
    }.toString(2)

    private fun readAll(file: File, format: ImportFormat = ChatImportParser.detectFormat(file)) =
        runBlocking {
            val seen = ArrayList<ImportedMessage>()
            ChatImportParser.forEachMessage(file, format) { seen.add(it) }
            seen
        }

    // ─── Zip, JSON transcript ──────────────────────────────────────────────

    @Test
    fun `reads the archive the exporter writes, header and all`() {
        // Deliberately nasty payloads: braces and quotes inside the text, a
        // "]," sequence that looks like the array boundary, newlines, and an
        // emoji. A reader that split the JSON textually instead of parsing it
        // would fail right here.
        val tricky = "closing } brace, a comma ], then \"quotes\"\nand a newline 🎈"
        val userParts = parts(textPart(tricky))
        val assistantParts = parts(
            textPart("Sure — see below."),
            JSONObject().put(
                "type",
                "toolUse",
            ).put("value", JSONObject().put("toolUseId", "t1").put("name", "shell_execute")),
        )
        val mediaParts = parts(
            textPart("here is a screenshot"),
            JSONObject().put(
                "type",
                "mediaRef",
            ).put(
                "value",
                JSONObject().put("relativePath", "media/2026/a.jpg").put("mimeType", "image/jpeg"),
            ),
        )
        val messages = JSONArray()
            .put(messageJson("m1", "user", userParts, 1_700_000_000_000L))
            .put(messageJson("m2", "assistant", assistantParts, 1_700_000_001_000L, reasoning = "thinking"))
            .put(messageJson("m3", "user", mediaParts, 1_700_000_002_000L))
            .toString()

        val zip = zipOf(
            "messages.json" to messages,
            "session.json" to exportHeader(),
        )

        assertEquals(ImportFormat.ZIP_JSON, ChatImportParser.detectFormat(zip))
        val header = ChatImportParser.readHeader(zip, ImportFormat.ZIP_JSON)
        assertEquals("Trip plan", header.title)
        assertEquals("cn:deepseek-v4.1-flash", header.modelId)
        assertEquals(1_700_000_000_000L, header.createdAt)
        assertEquals(1_700_000_060_000L, header.updatedAt)

        val read = readAll(zip)
        assertEquals(3, read.size)
        // Byte-for-byte parts_json, so an imported row renders exactly like a
        // native one (tool blocks included).
        assertEquals(listOf("user", "assistant", "user"), read.map { it.role })
        assertEquals(userParts, read[0].partsJson)
        assertEquals(assistantParts, read[1].partsJson)
        assertEquals("thinking", read[1].reasoning)
        assertEquals(1_700_000_002_000L, read[2].createdAt)
        assertEquals(listOf(0, 0, 1), read.map { it.mediaRefs })
    }

    @Test
    fun `finds the transcript in a re-zipped copy that nests the entries`() {
        val zip = zipOf(
            "MyExport/messages.json" to JSONArray()
                .put(messageJson("m1", "user", parts(textPart("nested"))))
                .toString(),
            "MyExport/session.json" to exportHeader(title = "Nested"),
        )
        assertEquals(ImportFormat.ZIP_JSON, ChatImportParser.detectFormat(zip))
        assertEquals("Nested", ChatImportParser.readHeader(zip, ImportFormat.ZIP_JSON).title)
        assertEquals(1, readAll(zip).size)
    }

    @Test
    fun `bare messages json array needs no sidecar`() {
        val file = write(
            "messages.json",
            JSONArray()
                .put(messageJson("m1", "user", parts(textPart("hi")), 5L))
                .put(messageJson("m2", "assistant", parts(textPart("hello"))))
                .toString(),
        )
        assertEquals(ImportFormat.JSON, ChatImportParser.detectFormat(file))

        val header = ChatImportParser.readHeader(file, ImportFormat.JSON)
        assertNull(header.title)
        assertNull(header.modelId)

        val read = readAll(file)
        assertEquals(2, read.size)
        assertEquals(5L, read[0].createdAt)
        // No timestamp in the source → none invented here (the importer spaces
        // those rows itself).
        assertNull(read[1].createdAt)
    }

    @Test
    fun `object shaped json keeps its session block`() {
        val file = write(
            "backup.json",
            JSONObject().apply {
                put("session", JSONObject().put("title", "From another app").put("model_id", "gpt-x"))
                put(
                    "messages",
                    JSONArray()
                        .put(messageJson("m1", "user", parts(textPart("one"))))
                        .put(messageJson("m2", "assistant", parts(textPart("two")))),
                )
            }.toString(),
        )
        val header = ChatImportParser.readHeader(file, ImportFormat.JSON)
        assertEquals("From another app", header.title)
        assertEquals("gpt-x", header.modelId)
        assertEquals(2, readAll(file).size)
    }

    // ─── Zip, plain-text transcript ────────────────────────────────────────

    @Test
    fun `reads the text transcript the exporter writes`() {
        val transcript = buildString {
            append("Trip plan\n")
            append("\n")
            append("You: where should we go in October?\n")
            append("\n")
            append("Assistant: Kyoto, probably.\n")
            append("The crowds thin out after the 20th.\n")
            append("\n")
            append("You: booked, thanks!\n")
            append("\n")
        }
        val zip = zipOf(
            "messages.txt" to transcript,
            "session.json" to exportHeader(),
        )
        assertEquals(ImportFormat.ZIP_TEXT, ChatImportParser.detectFormat(zip))

        val read = readAll(zip)
        assertEquals(3, read.size)
        assertEquals(listOf("user", "assistant", "user"), read.map { it.role })
        assertEquals(
            JSONArray().put(textPart("where should we go in October?")).toString(),
            read[0].partsJson,
        )
        // A wrapped assistant turn stays ONE message, newline intact.
        assertEquals(
            JSONArray().put(textPart("Kyoto, probably.\nThe crowds thin out after the 20th.")).toString(),
            read[1].partsJson,
        )
        assertNull(read[0].createdAt)
    }

    @Test
    fun `bare text file is imported, not rejected`() {
        val file = write(
            "notes.txt",
            "Notes from the trip\n\nUser: first\n\nAssistant: second\n",
        )
        assertEquals(ImportFormat.TEXT, ChatImportParser.detectFormat(file))
        assertEquals("Notes from the trip", ChatImportParser.readHeader(file, ImportFormat.TEXT).title)

        val read = readAll(file)
        assertEquals(2, read.size)
        assertEquals("first", firstText(read[0].partsJson))
        assertEquals("second", firstText(read[1].partsJson))
    }

    @Test
    fun `a text file with no speaker markers becomes one user message`() {
        val file = write("jottings.txt", "just some prose\nwith a second line\n")
        val read = readAll(file)
        assertEquals(1, read.size)
        assertEquals("user", read[0].role)
        assertEquals("just some prose\nwith a second line", firstText(read[0].partsJson))
    }

    // ─── Robustness ────────────────────────────────────────────────────────

    @Test
    fun `a large transcript streams without any single-message buffer limit`() {
        val count = 800
        val messages = StringBuilder("[")
        for (i in 0 until count) {
            if (i > 0) messages.append(',')
            messages.append(
                messageJson("m$i", if (i % 2 == 0) "user" else "assistant", parts(textPart("msg $i"))),
            )
        }
        messages.append(']')
        val file = write("messages.json", messages.toString())

        val read = readAll(file)
        assertEquals(count, read.size)
        assertEquals("msg ${count - 1}", firstText(read.last().partsJson))
    }

    @Test
    fun `content that is not a parts array is wrapped as text rather than dropped`() {
        val file = write(
            "messages.json",
            JSONArray().put(JSONObject().put("role", "user").put("content", "plain string body")).toString(),
        )
        val read = readAll(file)
        assertEquals(1, read.size)
        assertEquals("plain string body", firstText(read[0].partsJson))
    }

    @Test
    fun `unknown roles and blank roles fall back to user`() {
        val file = write(
            "messages.json",
            JSONArray()
                .put(JSONObject().put("role", "SYSTEM").put("content", parts(textPart("a"))))
                .put(JSONObject().put("role", "wizard").put("content", parts(textPart("b"))))
                .put(JSONObject().put("content", parts(textPart("c"))))
                .toString(),
        )
        val read = readAll(file)
        assertEquals(listOf("system", "user", "user"), read.map { it.role })
    }

    @Test
    fun `rejects a payload with nothing to import`() {
        val empty = temp.newFile("empty.zip").apply { writeBytes(ByteArray(0)) }
        try {
            ChatImportParser.detectFormat(empty)
            fail("an empty file must not be importable")
        } catch (e: ChatImportException) {
            assertTrue(e.message!!.contains("empty", ignoreCase = true))
        }

        val zipWithoutTranscript = zipOf("session.json" to exportHeader())
        try {
            ChatImportParser.detectFormat(zipWithoutTranscript)
            fail("an archive with no transcript must not be importable")
        } catch (e: ChatImportException) {
            assertTrue(e.message!!.contains("messages.json"))
        }

        val garbage = write("random.txt", "this is not a transcript at all")
        // Readable as text, but it holds no speaker markers and no prose the
        // user would recognise as a chat: the importer still takes it (one
        // user message) — asserting the deliberate leniency, not a rejection.
        assertEquals(1, readAll(garbage).size)
    }

    @Test
    fun `truncated json fails loudly instead of importing half a chat`() {
        val file = write(
            "messages.json",
            """[{"role":"user","content":"[{\"type\":\"text\",\"value\":\"ok\"}]"},{"role":"assist""",
        )
        try {
            readAll(file)
            fail("a truncated array must raise ChatImportException")
        } catch (e: ChatImportException) {
            assertTrue(e.message!!.contains("JSON", ignoreCase = true))
        }
    }

    @Test
    fun `utf8 text survives the round trip`() {
        val zip = zipOf(
            "messages.txt" to "中文标题\n\nYou: 你好，世界\n\nAssistant: 你好！\n",
            "session.json" to exportHeader(title = "中文标题"),
        )
        val read = readAll(zip)
        assertEquals("中文标题", ChatImportParser.readHeader(zip, ImportFormat.ZIP_TEXT).title)
        assertEquals("你好，世界", firstText(read[0].partsJson))
        assertEquals("你好！", firstText(read[1].partsJson))
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun firstText(partsJson: String): String =
        JSONArray(partsJson).getJSONObject(0).getString("value")
}
