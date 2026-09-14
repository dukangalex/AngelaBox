package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import kotlinx.coroutines.launch

/**
 * Pull the page down from the top to go back. Nested screens pop the parent;
 * tab roots (log / tools / settings) return to the dashboard.
 */
@Composable
fun PullToPopContainer(
    enabled: Boolean,
    releaseHint: String,
    onPop: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val threshold = with(density) { 72.dp.toPx() }
    val maxPull = threshold * 1.7f
    var pull by remember { mutableFloatStateOf(0f) }
    val bounce = remember { Animatable(0f) }
    val enabledState = rememberUpdatedState(enabled)
    val onPopState = rememberUpdatedState(onPop)
    var armed by remember { mutableStateOf(false) }
    var bouncing by remember { mutableStateOf(false) }
    val shown = if (bouncing) bounce.value else pull

    LaunchedEffect(pull >= threshold, enabled) {
        if (!enabled) {
            armed = false
            return@LaunchedEffect
        }
        if (pull >= threshold && !armed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            armed = true
        } else if (pull < threshold) {
            armed = false
        }
    }

    fun settle() {
        if (!enabledState.value) {
            pull = 0f
            return
        }
        if (pull >= threshold) {
            val callback = onPopState.value
            pull = 0f
            armed = false
            callback()
            return
        }
        val from = pull
        pull = 0f
        bouncing = true
        scope.launch {
            bounce.snapTo(from)
            bounce.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
            bouncing = false
        }
    }

    val connection = remember(threshold, maxPull) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabledState.value || source != NestedScrollSource.UserInput) return Offset.Zero
                if (pull > 0f && available.y < 0f) {
                    val next = (pull + available.y).coerceAtLeast(0f)
                    val consumed = next - pull
                    pull = next
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!enabledState.value || source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y > 0f) {
                    val next = (pull + available.y * 0.42f).coerceAtMost(maxPull)
                    val consumedY = (next - pull) / 0.42f
                    pull = next
                    return Offset(0f, consumedY.coerceAtMost(available.y))
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!enabledState.value) return Velocity.Zero
                val shouldPop = pull >= threshold
                settle()
                return if (shouldPop) available else Velocity.Zero
            }
        }
    }

    val dragHint = stringResource(R.string.pull_drag_hint)
    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(connection),
    ) {
        if (shown > 6f) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = if (armed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = if (armed) releaseHint else dragHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (armed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = shown * 0.9f },
        ) {
            content()
        }
    }
}
