package io.nekohasekai.sfa.inspection

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InspectionProvidersTest {
    @Test fun presetsAreDistinctHttpsEndpointsWithSelectableModels() {
        val presets = InspectionProviders.presets
        assertEquals(3, presets.size)
        assertEquals(presets.size, presets.map { it.endpoint }.toSet().size)
        presets.forEach { preset ->
            assertTrue(preset.endpoint.startsWith("https://"))
            assertTrue(preset.models.isNotEmpty())
            preset.models.forEach { model ->
                assertEquals(preset, InspectionProviders.matching(preset.endpoint, model))
                InspectionAi.validateConfig(InspectionStore.AiConfig(preset.endpoint, model, "test-key"))
            }
        }
    }

    @Test fun unknownLegacyEndpointsAndModelsStayCustom() {
        assertNull(InspectionProviders.matching("https://custom.example/chat/completions", "other-model"))
        assertNull(InspectionProviders.matching(InspectionProviders.presets.first().endpoint, "other-model"))
    }

    @Test fun existingKeyIsNeverReusedAtAnotherEndpoint() {
        val old = InspectionStore.AiConfig("https://old.example/chat/completions", "model", "old-key")
        assertEquals("old-key", InspectionProviders.keyFor(old.endpoint, "", old))
        assertEquals("", InspectionProviders.keyFor("https://new.example/chat/completions", "", old))
        assertEquals("new-key", InspectionProviders.keyFor("https://new.example/chat/completions", "new-key", old))
    }

    @Test fun currentOpenAiPresetUsesSupportedCompletionBudgetParameter() {
        val openAi = InspectionProviders.presets.first()
        val body = JSONObject(InspectionAi.requestBody("payload", openAi.models.first(), openAi.endpoint))
        assertEquals(1200, body.getInt("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
        val other = JSONObject(InspectionAi.requestBody("payload", "deepseek-flash", InspectionProviders.presets[1].endpoint))
        assertEquals(1200, other.getInt("max_tokens"))
    }
}
