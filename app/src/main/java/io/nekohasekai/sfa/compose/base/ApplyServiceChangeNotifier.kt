package io.nekohasekai.sfa.compose.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun rememberApplyServiceChangeNotifier(
    serviceStatus: Status,
): (UiEvent.ApplyServiceChange.Mode) -> Unit = remember(serviceStatus) {
    { mode ->
        GlobalEventBus.tryEmit(UiEvent.ApplyServiceChange(mode))
    }
}
