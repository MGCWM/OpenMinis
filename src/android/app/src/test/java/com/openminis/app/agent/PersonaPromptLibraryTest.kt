package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPromptLibraryTest {

    @Test
    fun `sanitize strips path and illegal characters`() {
        assertEquals("persona.md", PersonaPromptLogic.sanitizeFileName(""))
        assertEquals("notes.md", PersonaPromptLogic.sanitizeFileName("/sdcard/Download/notes.md"))
        assertEquals("a_b.txt", PersonaPromptLogic.sanitizeFileName("a:b.txt"))
        assertEquals("persona.md", PersonaPromptLogic.sanitizeFileName(".."))
    }

    @Test
    fun `unique names get a numeric suffix`() {
        val existing = setOf("voice.md", "voice (2).md")
        assertEquals("other.md", PersonaPromptLogic.uniqueDisplayName("other.md", existing))
        assertEquals("voice (3).md", PersonaPromptLogic.uniqueDisplayName("voice.md", existing))
    }

    @Test
    fun `only md txt markdown are importable names`() {
        assertTrue(PersonaPromptLogic.isImportableName("a.md"))
        assertTrue(PersonaPromptLogic.isImportableName("a.TXT"))
        assertTrue(PersonaPromptLogic.isImportableName("a.markdown"))
        assertFalse(PersonaPromptLogic.isImportableName("a.pdf"))
        assertFalse(PersonaPromptLogic.isImportableName("a"))
        assertTrue(PersonaPromptLogic.isImportableMime("text/plain"))
        assertTrue(PersonaPromptLogic.isImportableMime("text/markdown"))
        assertFalse(PersonaPromptLogic.isImportableMime(null))
        assertFalse(PersonaPromptLogic.isImportableMime("application/pdf"))
        assertFalse(PersonaPromptLogic.isImportableMime("application/octet-stream"))
    }

    @Test
    fun `frontmatter is stripped on import`() {
        val raw = """
            ---
            name: "Minis"
            lang: "auto"
            ---
            Be terse.
        """.trimIndent()
        assertEquals("Be terse.", PersonaPromptLogic.extractImportedBody(raw).trim())
    }

    @Test
    fun `plain text import keeps the whole body`() {
        assertEquals("hello", PersonaPromptLogic.extractImportedBody("hello"))
    }

    @Test
    fun `bom is stripped`() {
        assertEquals("hi", PersonaPromptLogic.extractImportedBody("\uFEFFhi"))
    }

    @Test
    fun `provider mapping wins then selected then builtin`() {
        val custom = PersonaPromptEntry("c1", "custom.md", "c1.md", builtin = false)
        val index = PersonaPromptIndex(
            prompts = listOf(PersonaPromptLogic.builtinEntry(), custom),
            selectedId = "c1",
            providerSelections = mapOf("p1" to PersonaPromptLogic.BUILTIN_ID),
        )
        assertEquals(PersonaPromptLogic.BUILTIN_ID, PersonaPromptLogic.resolveId(index, "p1"))
        assertEquals("c1", PersonaPromptLogic.resolveId(index, "p-missing"))
        assertEquals("c1", PersonaPromptLogic.resolveId(index, null))
    }

    @Test
    fun `stale provider mapping falls back`() {
        val index = PersonaPromptIndex(
            prompts = listOf(PersonaPromptLogic.builtinEntry()),
            selectedId = PersonaPromptLogic.BUILTIN_ID,
            providerSelections = mapOf("p1" to "gone"),
        )
        assertEquals(PersonaPromptLogic.BUILTIN_ID, PersonaPromptLogic.resolveId(index, "p1"))
    }

    @Test
    fun `codec round trips and injects builtin`() {
        val json = PersonaPromptCodec.serialize(
            PersonaPromptIndex(
                prompts = listOf(
                    PersonaPromptEntry("c1", "a.md", "c1.md", builtin = false),
                ),
                selectedId = "c1",
                providerSelections = mapOf("prov" to "c1"),
            ),
        )
        val back = PersonaPromptCodec.parse(json)
        assertTrue(back.prompts.any { it.id == PersonaPromptLogic.BUILTIN_ID && it.builtin })
        assertEquals("c1", back.selectedId)
        assertEquals("c1", back.providerSelections["prov"])
        assertEquals("a.md", back.prompts.first { it.id == "c1" }.fileName)
    }

    @Test
    fun `normalize drops unknown selected id`() {
        val index = PersonaPromptLogic.normalizeIndex(
            PersonaPromptIndex(
                prompts = emptyList(),
                selectedId = "nope",
                providerSelections = mapOf("p" to "nope"),
            ),
        )
        assertEquals(PersonaPromptLogic.BUILTIN_ID, index.selectedId)
        assertTrue(index.providerSelections.isEmpty())
        assertEquals(1, index.prompts.size)
    }
}
