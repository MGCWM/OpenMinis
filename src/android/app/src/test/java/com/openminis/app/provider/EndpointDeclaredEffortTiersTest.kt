package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-reasoning-effort-endpoint-declared] An OpenAI-compatible gateway can
 * publish the effort tiers a model actually accepts, in `/v1/models`:
 *
 * ```
 * { "id": "cn:deepseek-v4.1-flash",
 *   "supports_reasoning": true,
 *   "reasoning_supported_efforts": ["low","high","max"] }
 * ```
 *
 * That is per-INSTANCE truth, and it is the only description of these models
 * the app can reach: the bundled models.dev registry matches by exact model id
 * (so it never matches a prefixed relay id like `cn:…` / `global:…`), and the
 * id-substring catalog in ThinkingLevelCatalog knows nothing about them. Before
 * this, every model behind such a gateway fell through to the conservative
 * XHIGH ceiling — the picker topped out at 超高, so the request went out as
 * `xhigh` and the gateway clamped it down to `high`. MAX was unreachable.
 *
 * The controls matter as much as the feature: endpoints that do not publish
 * these fields (official OpenAI and everything else) must parse exactly as
 * before.
 */
class EndpointDeclaredEffortTiersTest {

    // ---- parsing ---------------------------------------------------------

    private fun fetchModels(body: String): List<LLMModel> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(body))
        server.start()
        return try {
            runBlocking {
                OpenAIModelsApi.fetchModels(
                    apiKey = "test-key",
                    // A non-OpenAI base URL keeps the gpt-/o1 prefix filter off,
                    // so prefixed relay ids survive the parse.
                    baseURL = server.url("/v1").toString().trimEnd('/'),
                    context = null,
                    forceRefresh = true,
                )
            }
        } finally {
            server.shutdown()
        }
    }

    private fun modelJson(
        id: String,
        tiers: String? = null,
        supportsReasoning: String? = null,
    ): String {
        val fields = mutableListOf("\"id\":\"$id\"", "\"name\":\"$id\"")
        if (tiers != null) fields += "\"reasoning_supported_efforts\":$tiers"
        if (supportsReasoning != null) fields += "\"supports_reasoning\":$supportsReasoning"
        return "{${fields.joinToString(",")}}"
    }

    @Test
    fun `endpoint-declared tiers are stored on the model`() {
        val models = fetchModels(
            """{"data":[${modelJson("cn:deepseek-v4.1-flash", "[\"low\",\"high\",\"max\"]", "true")}]}""",
        )
        val model = models.single()
        assertEquals(listOf("low", "high", "max"), model.reasoningEffortValues)
        assertEquals(true, model.supportsReasoning)
    }

    @Test
    fun `tiers are normalized and unknown tokens dropped`() {
        // Case and stray whitespace are the gateway's business; the ladder is
        // ours. An unknown token must not survive: clampEffort maps the
        // declared list onto the ladder, and a list of nothing but unknowns
        // resolves to an empty ladder — which silently disables the clamp.
        val models = fetchModels(
            """{"data":[${modelJson("cn:mixed", "[\" High \",\"low\",\"ULTRA\",\"low\",\"bogus\",\"max\"]")}]}""",
        )
        assertEquals(listOf("high", "low", "max"), models.single().reasoningEffortValues)
    }

    @Test
    fun `an empty tier array means undeclared, not none`() {
        // "" is not a declaration of anything. Treating it as "this model takes
        // no effort parameter" would suppress the field on every gateway that
        // ships an empty placeholder list.
        val models = fetchModels("""{"data":[${modelJson("cn:auto", "[]")}]}""")
        val model = models.single()
        assertNull(model.reasoningEffortValues)
        assertFalse(model.declaresNoEffortTiers == true)
    }

    @Test
    fun `a silent endpoint parses exactly as before`() {
        val models = fetchModels("""{"data":[${modelJson("some-relay-model")}]}""")
        val model = models.single()
        assertNull(model.reasoningEffortValues)
        assertNull(model.supportsReasoning)
    }

    @Test
    fun `supports_reasoning alone still enables the thinking toggle`() {
        // Models with no tier control (kimi-k2.8-preview and friends) declare
        // only the boolean. The toggle must still light up.
        val models = fetchModels("""{"data":[${modelJson("cn:kimi-k2.8-preview", null, "true")}]}""")
        val model = models.single()
        assertEquals(true, model.supportsReasoning)
        assertNull(model.reasoningEffortValues)
    }

    @Test
    fun `a false supports_reasoning does not disable a known reasoning family`() {
        // Only an affirmative answer is taken from the endpoint; a gateway that
        // says `false` about a gpt-5.x id must not switch its toggle off.
        val models = fetchModels("""{"data":[${modelJson("gpt-5.6-luna", null, "false")}]}""")
        assertEquals(true, models.single().supportsReasoning)
    }

    // ---- ceiling / picker -------------------------------------------------

    private fun model(id: String, tiers: List<String>?) = LLMModel(
        id = id,
        displayName = id,
        provider = "Custom",
        reasoningEffortValues = tiers,
    )

    @Test
    fun `declared tiers raise the ceiling to the highest declared tier`() {
        val cn = model("cn:deepseek-v4.1-flash", listOf("low", "high", "max"))
        assertEquals(ThinkingLevel.MAX, cn.catalogMaxThinkingLevel)
        assertEquals(
            listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX),
            cn.selectableThinkingLevels,
        )
    }

    @Test
    fun `a route that only accepts high is capped at high`() {
        // global:deepseek-v4.1-flash really does declare ["high"] — offering
        // anything above it would only produce a downgrade round-trip.
        val global = model("global:deepseek-v4.1-flash", listOf("high"))
        assertEquals(ThinkingLevel.HIGH, global.catalogMaxThinkingLevel)
        assertEquals(listOf(ThinkingLevel.HIGH), global.selectableThinkingLevels)
    }

    @Test
    fun `a prefixed relay id with no declaration still falls back to XHIGH`() {
        // Regression control for the state this fix came from: nothing about a
        // prefixed id alone may change the ceiling.
        assertEquals(
            ThinkingLevel.XHIGH,
            model("cn:deepseek-v4.1-flash", null).catalogMaxThinkingLevel,
        )
    }

    @Test
    fun `a model that cannot reason stays OFF regardless of tiers`() {
        val noReasoning = model("cn:image-model", listOf("low", "high", "max"))
            .copy(supportsReasoning = false)
        assertEquals(ThinkingLevel.OFF, noReasoning.catalogMaxThinkingLevel)
    }

    // ---- wire ------------------------------------------------------------

    private fun wireBody(
        modelId: String,
        level: ThinkingLevel,
        declared: List<String>?,
    ): JSONObject {
        val body = JSONObject()
        ThinkingRuleResolver.apply(
            body,
            ThinkingResolveContext(
                modelId = modelId,
                supportsReasoning = true,
                declaredEffortValues = declared,
                declaresNoEffortTiers = false,
                level = level,
                maxTokens = 4096,
                isOpenRouter = false,
                usesUnifiedReasoningEffort = false,
                isMistral = false,
                isDashScope = false,
                isXAI = false,
                offEffort = null,
            ),
        )
        return body
    }

    @Test
    fun `selecting the top tier reaches max on the wire`() {
        // The whole point: 极高 → "max" on a route that declared it.
        val body = wireBody("cn:deepseek-v4.1-flash", ThinkingLevel.MAX, listOf("low", "high", "max"))
        assertEquals("max", body.getString("reasoning_effort"))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun `a tier the route did not declare is clamped down, not sent`() {
        // xhigh is not in ["low","high","max"], so a persisted 超高 must not
        // reach a backend that would reject it.
        val body = wireBody("cn:deepseek-v4.1-flash", ThinkingLevel.XHIGH, listOf("low", "high", "max"))
        assertEquals("high", body.getString("reasoning_effort"))
    }

    @Test
    fun `an undeclared model keeps its unclamped behaviour`() {
        val body = wireBody("cn:deepseek-v4.1-flash", ThinkingLevel.XHIGH, null)
        assertEquals("xhigh", body.getString("reasoning_effort"))
    }

    @Test
    fun `a single-tier route always sends that tier`() {
        val body = wireBody("global:deepseek-v4.1-flash", ThinkingLevel.MAX, listOf("high"))
        assertEquals("high", body.getString("reasoning_effort"))
        assertTrue(body.has("reasoning_effort"))
    }
}
