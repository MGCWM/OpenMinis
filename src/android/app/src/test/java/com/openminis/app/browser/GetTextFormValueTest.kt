package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-gettext-form-values-2026-10] get_text on an input/textarea/select
 * used to return "" (innerText of a form control is empty) right after a
 * successful type/fill_form — the agent had to fall back to execute_js just to
 * verify what it had typed. The JS now reports `value` (+ selected option
 * labels) for form controls.
 *
 * Source-pinned because the snippet is a Kotlin string template executed in
 * the page; this guards the branch itself.
 */
class GetTextFormValueTest {

    private val js = File("src/main/java/com/openminis/app/browser/BrowserUseJS.kt").readText()

    private fun getTextFun(): String {
        val start = js.indexOf("fun getText(selector: String?): String {")
        val end = js.indexOf("// -- Get Readable --", start)
        return js.substring(start, end)
    }

    @Test
    fun `form controls report their value instead of empty innerText`() {
        val f = getTextFun()
        assertTrue("branches on INPUT/TEXTAREA/SELECT", f.contains("tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'"))
        assertTrue("reads el.value", f.contains("el.value"))
        assertTrue("selects include the selected option label", f.contains("selectedOptions"))
        assertTrue("result is capped the same as the innerText path", f.contains("substring(0, 10000)"))
        assertTrue("response carries kind=value so callers can tell the two apart", f.contains("kind: 'value'"))
    }

    @Test
    fun `plain elements keep the innerText path`() {
        val f = getTextFun()
        assertTrue(f.contains("var text = innerTextVal.substring(0, 10000);"))
    }
}
