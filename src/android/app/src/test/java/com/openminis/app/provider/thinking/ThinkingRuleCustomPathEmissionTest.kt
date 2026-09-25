package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three user-pickable escape-hatch formats used to be declared but never
 * emitted: the rule editor offered them, the resolver's `when` had no branch for
 * them, and the `else` called `error(...)`. A rule using one of them therefore
 * failed the request instead of shaping it.
 *
 * These tests pin the emission, and — just as important — the degradation path:
 * anything a rule cannot express (blank path, no values, a path blocked by a real
 * request field) emits NOTHING rather than throwing or clobbering the payload.
 */
class ThinkingRuleCustomPathEmissionTest {

    private companion object {
        const val INSTANCE = "inst-effort"
    }

    @After
    fun tearDown() {
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
    }

    private fun ctx(
        modelId: String = "cn:some-model",
        level: ThinkingLevel = ThinkingLevel.HIGH,
        supportsReasoning: Boolean? = true,
    ) = ThinkingResolveContext(
        modelId = modelId,
        instanceId = INSTANCE,
        supportsReasoning = supportsReasoning,
        declaredEffortValues = null,
        level = level,
        maxTokens = 4096,
        isOpenRouter = false,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        offEffort = null,
    )

    private fun install(format: ThinkingWireFormat, pattern: String = "*") {
        ThinkingRuleResolver.setCustomRules(
            INSTANCE,
            listOf(
                ThinkingRule(
                    kind = ThinkingRule.Kind.CUSTOM,
                    scope = ThinkingRule.Scope.ModelPattern(pattern),
                    wireFormat = format,
                    label = "test-rule",
                ),
            ),
        )
    }

    /** A realistic starting payload: the rule layer mutates a real request body. */
    private fun body() = JSONObject()
        .put("model", "cn:some-model")
        .put("max_tokens", 4096)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))

    // ---- CustomPath ------------------------------------------------------

    @Test
    fun `custom path writes the value at a root path`() {
        install(ThinkingWireFormat.CustomPath("reasoning_effort", mapOf(ThinkingLevel.HIGH to "max"), null))
        val b = body()
        val trace = ThinkingRuleResolver.apply(b, ctx(level = ThinkingLevel.HIGH))
        assertEquals("max", b.getString("reasoning_effort"))
        assertEquals("test-rule", trace.matchedRuleLabel)
        assertEquals("max", trace.clampedTo)
    }

    @Test
    fun `a single-field rule answers every level instead of emitting nothing`() {
        // The editor only asks for the HIGH value, so a rule is normally a single
        // entry. Clamping onto it (down then up, like clampEffort) is what makes
        // 低/中/超高/极高 still resolve — and it is exactly how you pin one value.
        install(ThinkingWireFormat.CustomPath("reasoning_effort", mapOf(ThinkingLevel.HIGH to "max"), null))
        for (level in listOf(
            ThinkingLevel.LOW,
            ThinkingLevel.MEDIUM,
            ThinkingLevel.HIGH,
            ThinkingLevel.XHIGH,
            ThinkingLevel.MAX,
            ThinkingLevel.ULTRA,
        )) {
            val b = body()
            ThinkingRuleResolver.apply(b, ctx(level = level))
            assertEquals("level $level must still emit", "max", b.optString("reasoning_effort"))
        }
    }

    @Test
    fun `with several tiers mapped the nearest lower one wins`() {
        install(
            ThinkingWireFormat.CustomPath(
                "reasoning_effort",
                mapOf(ThinkingLevel.LOW to "low", ThinkingLevel.HIGH to "high"),
                null,
            ),
        )
        val medium = body()
        ThinkingRuleResolver.apply(medium, ctx(level = ThinkingLevel.MEDIUM))
        assertEquals("low", medium.getString("reasoning_effort"))

        val ultra = body()
        ThinkingRuleResolver.apply(ultra, ctx(level = ThinkingLevel.ULTRA))
        assertEquals("high", ultra.getString("reasoning_effort"))
    }

    @Test
    fun `a dotted path creates the intermediate objects`() {
        install(
            ThinkingWireFormat.CustomPath(
                "thinking.enabled",
                mapOf(ThinkingLevel.HIGH to "true"),
                null,
            ),
        )
        val b = body()
        ThinkingRuleResolver.apply(b, ctx())
        assertEquals("true", b.getJSONObject("thinking").getString("enabled"))
    }

    @Test
    fun `off emits nothing unless the rule gave an off value`() {
        install(ThinkingWireFormat.CustomPath("reasoning_effort", mapOf(ThinkingLevel.HIGH to "max"), null))
        val b = body()
        ThinkingRuleResolver.apply(b, ctx(level = ThinkingLevel.OFF))
        assertFalse("an unset off value must omit the field: $b", b.has("reasoning_effort"))
    }

    @Test
    fun `off writes the off value when the rule gave one`() {
        install(
            ThinkingWireFormat.CustomPath(
                "reasoning_effort",
                mapOf(ThinkingLevel.HIGH to "max"),
                offValue = "none",
            ),
        )
        val b = body()
        ThinkingRuleResolver.apply(b, ctx(level = ThinkingLevel.OFF))
        assertEquals("none", b.getString("reasoning_effort"))
    }

    @Test
    fun `a blank path emits nothing instead of throwing`() {
        install(ThinkingWireFormat.CustomPath("", mapOf(ThinkingLevel.HIGH to "max"), null))
        val b = body()
        ThinkingRuleResolver.apply(b, ctx())
        assertFalse(b.has("reasoning_effort"))
    }

    @Test
    fun `a path of empty segments emits nothing instead of throwing`() {
        install(ThinkingWireFormat.CustomPath(" .. . ", mapOf(ThinkingLevel.HIGH to "max"), null))
        val b = body()
        ThinkingRuleResolver.apply(b, ctx())
        assertFalse(b.has("reasoning_effort"))
    }

    @Test
    fun `a rule with no values emits nothing instead of throwing`() {
        install(ThinkingWireFormat.CustomPath("reasoning_effort", emptyMap(), null))
        val b = body()
        ThinkingRuleResolver.apply(b, ctx())
        assertFalse(b.has("reasoning_effort"))
    }

    @Test
    fun `a path blocked by a real request field is left alone, not clobbered`() {
        // `messages` is an array and `model` is a string: a hand-typed path that
        // walks into either must be refused, never replaced with an object.
        install(ThinkingWireFormat.CustomPath("messages.role", mapOf(ThinkingLevel.HIGH to "system"), null))
        val b = body()
        ThinkingRuleResolver.apply(b, ctx())
        assertTrue(b.getJSONArray("messages").getJSONObject(0).getString("role") == "user")

        install(ThinkingWireFormat.CustomPath("model.sub", mapOf(ThinkingLevel.HIGH to "x"), null))
        val b2 = body()
        ThinkingRuleResolver.apply(b2, ctx())
        assertEquals("cn:some-model", b2.getString("model"))
    }

    // ---- BooleanToggle / ExtraBodyToggle ---------------------------------

    @Test
    fun `boolean toggle follows the thinking switch`() {
        install(ThinkingWireFormat.BooleanToggle("thinking"))
        val on = body()
        ThinkingRuleResolver.apply(on, ctx(level = ThinkingLevel.MEDIUM))
        assertTrue(on.getBoolean("thinking"))

        val off = body()
        ThinkingRuleResolver.apply(off, ctx(level = ThinkingLevel.OFF))
        assertFalse(off.getBoolean("thinking"))
    }

    @Test
    fun `extra body toggle nests under extra_body`() {
        install(ThinkingWireFormat.ExtraBodyToggle("thinking.enabled"))
        val on = body()
        ThinkingRuleResolver.apply(on, ctx(level = ThinkingLevel.HIGH))
        assertTrue(on.getJSONObject("extra_body").getJSONObject("thinking").getBoolean("enabled"))

        val off = body()
        ThinkingRuleResolver.apply(off, ctx(level = ThinkingLevel.OFF))
        assertFalse(off.getJSONObject("extra_body").getJSONObject("thinking").getBoolean("enabled"))
    }

    @Test
    fun `extra body toggle accepts a path that already spells out the wrapper`() {
        install(ThinkingWireFormat.ExtraBodyToggle("extra_body.thinking.enabled"))
        val b = body()
        ThinkingRuleResolver.apply(b, ctx())
        assertTrue(b.getJSONObject("extra_body").getJSONObject("thinking").getBoolean("enabled"))
    }

    @Test
    fun `extra body toggle never clobbers a non-object extra_body`() {
        install(ThinkingWireFormat.ExtraBodyToggle("thinking.enabled"))
        val b = body().put("extra_body", "not-an-object")
        ThinkingRuleResolver.apply(b, ctx())
        assertEquals("not-an-object", b.getString("extra_body"))
    }

    // ---- regression: the reported bug ------------------------------------

    @Test
    fun `no user-pickable format throws on the openai path`() {
        // The bug: three of the eight formats the editor offers had no branch in
        // emit() and hit `else -> error(...)`. Every format a user can select must
        // resolve to a body (or to nothing), never to an exception.
        val pickable = listOf(
            ThinkingWireFormat.OmitEverything,
            ThinkingWireFormat.ReasoningEffort(null),
            ThinkingWireFormat.ReasoningEffortNested(null),
            ThinkingWireFormat.BooleanToggle("thinking"),
            ThinkingWireFormat.ExtraBodyToggle("thinking.enabled"),
            ThinkingWireFormat.DeepSeekSibling,
            ThinkingWireFormat.QwenDual,
            ThinkingWireFormat.CustomPath("reasoning_effort", mapOf(ThinkingLevel.HIGH to "max"), null),
        )
        for (format in pickable) {
            install(format)
            for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MAX)) {
                ThinkingRuleResolver.apply(body(), ctx(level = level)) // must not throw
            }
        }
    }

    @Test
    fun `a persisted custom-path rule round-trips and then emits`() {
        // The coding layer already handled CustomPath; this pins that a rule
        // written by the editor and read back from Room behaves identically.
        val encoded = ThinkingRuleCoding.encodeWireFormat(
            ThinkingWireFormat.CustomPath(
                "reasoning_effort",
                mapOf(ThinkingLevel.HIGH to "max"),
                offValue = null,
            ),
        )
        val decoded = ThinkingRuleCoding.decodeWireFormat(encoded)
        assertEquals(
            ThinkingWireFormat.CustomPath("reasoning_effort", mapOf(ThinkingLevel.HIGH to "max"), null),
            decoded,
        )
        install(decoded!!)
        val b = body()
        ThinkingRuleResolver.apply(b, ctx(level = ThinkingLevel.MAX))
        assertEquals("max", b.getString("reasoning_effort"))
    }
}
