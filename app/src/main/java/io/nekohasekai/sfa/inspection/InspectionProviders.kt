package io.nekohasekai.sfa.inspection

/** Fixed, public HTTPS endpoints. Choosing a preset never makes a network request. */
internal object InspectionProviders {
    data class Preset(val name: String, val endpoint: String, val models: List<String>)

    // See provider OpenAI-compatible chat/completions documentation; models and access may change.
    val presets = listOf(
        Preset("OpenAI", "https://api.openai.com/v1/chat/completions", listOf("gpt-5.4-mini")),
        Preset("DeepSeek", "https://api.deepseek.com/chat/completions", listOf("deepseek-flash", "deepseek-v4-pro")),
        Preset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", listOf("gemini-4-hydrogen", "gemini-3.8-flash")),
    )

    // Only exact matches are presets; preserve any legacy/custom endpoint or model unchanged.
    fun matching(endpoint: String, model: String): Preset? = presets.firstOrNull { it.endpoint == endpoint && model in it.models }

    fun keyFor(endpoint: String, enteredKey: String, existing: InspectionStore.AiConfig?): String = enteredKey.ifBlank { existing?.key?.takeIf { existing.endpoint == endpoint }.orEmpty() }
}
