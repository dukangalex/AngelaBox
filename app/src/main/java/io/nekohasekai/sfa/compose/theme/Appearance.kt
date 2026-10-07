package io.nekohasekai.sfa.compose.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sfa.database.Settings

object Appearance {
    var mode by mutableStateOf("system")
        private set
    var seed by mutableStateOf("default")
        private set
    var pureBlack by mutableStateOf(false)
        private set
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        mode = Settings.themeMode.ifBlank { "system" }
        seed = Settings.themeSeed.ifBlank { "default" }
        pureBlack = Settings.themePureBlack
    }

    // apply* names avoid clashing with the generated property setters.
    fun applyMode(value: String) {
        val next = when (value) {
            "light", "dark" -> value
            else -> "system"
        }
        Settings.themeMode = next
        mode = next
    }

    fun applySeed(value: String) {
        Settings.themeSeed = value.ifBlank { "default" }
        seed = Settings.themeSeed
    }

    fun applyPureBlack(value: Boolean) {
        Settings.themePureBlack = value
        pureBlack = value
    }
}
