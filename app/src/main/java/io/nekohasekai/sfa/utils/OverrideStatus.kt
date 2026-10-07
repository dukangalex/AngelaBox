package io.nekohasekai.sfa.utils

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OverrideNotice(
    val title: String,
    val reason: String,
    val hint: String,
    val error: Boolean = false,
)

object OverrideStatus {
    private val _notices = MutableStateFlow<List<OverrideNotice>>(emptyList())
    val notices: StateFlow<List<OverrideNotice>> = _notices

    fun clear() {
        _notices.value = emptyList()
    }

    fun set(items: List<OverrideNotice>) {
        _notices.value = items
        val errors = items.filter { it.error }
        if (errors.isNotEmpty()) {
            val text = errors.joinToString("\n\n") { "${it.title}\n${it.reason}\n${it.hint}" }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(Application.application, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun add(item: OverrideNotice) {
        set(_notices.value + item)
    }
}
