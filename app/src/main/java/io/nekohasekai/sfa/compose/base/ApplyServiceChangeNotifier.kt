package io.nekohasekai.sfa.compose.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Emits an apply-change request. Whether the service is running is checked by
 * the collector in MainActivity, so the notifier needs no status.
 */
@Composable
fun rememberApplyServiceChangeNotifier(): (UiEvent.ApplyServiceChange.Mode) -> Unit = remember {
    { mode ->
        GlobalEventBus.tryEmit(UiEvent.ApplyServiceChange(mode))
    }
}
