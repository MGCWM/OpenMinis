package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-browser-observability] Input parsing for the agent extensions:
 * upload_file's `files` accept both a JSON array and the schema-faithful
 * encoded-string form; console/network filters parse from their fields.
 */
class BrowserActionInputTest {

    @Test
    fun `files parse from a real array and from an encoded string`() {
        val a = BrowserActionInput.parse(
            """{"action":"upload_file","files":["/var/minis/workspace/a.pdf","b.png"]}"""
        )!!
        assertEquals(listOf("/var/minis/workspace/a.pdf", "b.png"), a.files)

        val b = BrowserActionInput.parse(
            """{"action":"upload_file","files":"[\"x.png\",\"y.png\"]"}"""
        )!!
        assertEquals(listOf("x.png", "y.png"), b.files)
    }

    @Test
    fun `log filters parse`() {
        val a = BrowserActionInput.parse("""{"action":"get_console_logs","log_level":"error"}""")!!
        assertEquals("error", a.logLevel)

        val b = BrowserActionInput.parse("""{"action":"get_network_log","filter":"api.example.com"}""")!!
        assertEquals("api.example.com", b.filter)
    }

    @Test
    fun `new actions resolve by name`() {
        assertEquals(
            BrowserAction.UPLOAD_FILE,
            BrowserActionInput.parse("""{"action":"upload_file"}""")?.action,
        )
        assertEquals(
            BrowserAction.GET_CONSOLE_LOGS,
            BrowserActionInput.parse("""{"action":"get_console_logs"}""")?.action,
        )
        assertEquals(
            BrowserAction.GET_NETWORK_LOG,
            BrowserActionInput.parse("""{"action":"get_network_log"}""")?.action,
        )
    }
}
