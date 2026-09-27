package com.openminis.app.share

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [T-android-batch-export] The multi-session archive contract: detection via
 * the top-level manifest, the manifest's session list, and prefixed reads that
 * stay inside one chosen folder.
 */
class ChatImportParserBatchTest {

    private fun put(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildBatchZip(dir: File): File {
        val zip = File(dir, "batch.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            put(
                zos,
                "minis-batch-manifest.json",
                """
                {"app":"minis-android","kind":"minis-batch","version":1,"format":"json",
                 "sessions":[
                   {"dir":"sessions/001_Alpha","title":"Alpha","message_count":2},
                   {"dir":"sessions/002_Beta","title":"Beta","message_count":1}
                 ]}
                """.trimIndent(),
            )
            put(zos, "sessions/001_Alpha/session.json", """{"title":"Alpha","model_id":"m1"}""")
            put(
                zos,
                "sessions/001_Alpha/messages.json",
                """[{"role":"user","content":"[]"},{"role":"assistant","content":"[]"}]""",
            )
            put(zos, "sessions/002_Beta/session.json", """{"title":"Beta"}""")
            put(zos, "sessions/002_Beta/messages.json", """[{"role":"user","content":"[]"}]""")
        }
        return zip
    }

    private fun buildPlainZip(dir: File): File {
        val zip = File(dir, "plain.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            put(zos, "session.json", """{"title":"Solo"}""")
            put(zos, "messages.json", """[{"role":"user","content":"[]"}]""")
        }
        return zip
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = File(System.getProperty("java.io.tmpdir"), "minis-batch-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `batch archive is detected and lists its sessions`() = withTempDir { dir ->
        val zip = buildBatchZip(dir)
        assertTrue(ChatImportParser.isBatchArchive(zip))
        val entries = ChatImportParser.readBatchManifest(zip)
        assertEquals(2, entries.size)
        assertEquals("sessions/001_Alpha", entries[0].dir)
        assertEquals("Alpha", entries[0].title)
        assertEquals(2, entries[0].messageCount)
        assertEquals("sessions/002_Beta", entries[1].dir)
        assertEquals(ImportFormat.ZIP_JSON, ChatImportParser.batchFormatFor(zip))
    }

    @Test
    fun `prefixed reads stay inside the chosen session`() = withTempDir { dir ->
        val zip = buildBatchZip(dir)
        val header = ChatImportParser.readHeader(zip, ImportFormat.ZIP_JSON, "sessions/002_Beta/")
        assertEquals("Beta", header.title)
        var count = 0
        runBlocking {
            count = ChatImportParser.forEachMessage(zip, ImportFormat.ZIP_JSON, "sessions/001_Alpha/") { }
        }
        assertEquals(2, count)
    }

    @Test
    fun `plain single archive is not a batch`() = withTempDir { dir ->
        val zip = buildPlainZip(dir)
        assertFalse(ChatImportParser.isBatchArchive(zip))
    }
}
