package com.androidharness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Hidden panes stay composed, preserving the chat composer and unsaved editor content. */
@Composable
internal fun WorkspaceSplitLayout(
    wide: Boolean,
    panelVisible: Boolean,
    chatFraction: Float,
    onResize: (Float) -> Unit,
    chat: @Composable () -> Unit,
    panel: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val available = (maxWidth.value - 24f).coerceAtLeast(1f)
        val scale = density.fontScale.coerceAtLeast(1f)
        val minimum = (320f * scale / available).coerceAtMost(0.5f)
        val maximum = (1f - 340f * scale / available).coerceAtLeast(minimum)
        val fraction = chatFraction.coerceIn(minimum, maximum)
        val pixels = with(density) { available.dp.toPx() }
        Row(Modifier.fillMaxSize()) {
            RetainedWorkspaceContent(visible = wide || !panelVisible,
                modifier = if (!wide && panelVisible) Modifier.width(0.dp) else Modifier.weight(if (wide && panelVisible) fraction else 1f),
                content = chat)
            if (wide && panelVisible) {
                Box(Modifier.width(24.dp).fillMaxHeight()
                    .semantics {
                        contentDescription = "Resize workspace panes"
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, minimum..maximum)
                        setProgress { onResize(it.coerceIn(minimum, maximum)); true }
                    }
                    .draggable(rememberDraggableState { delta -> onResize((fraction + delta / pixels).coerceIn(minimum, maximum)) }, Orientation.Horizontal),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }
            RetainedWorkspaceContent(visible = panelVisible,
                modifier = if (!panelVisible) Modifier.width(0.dp) else Modifier.weight(if (wide) 1f - fraction else 1f),
                content = panel)
        }
    }
}

internal val LocalWorkspacePaneActive = staticCompositionLocalOf { true }

@Composable
internal fun RetainedWorkspaceContent(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val lastSize = remember { intArrayOf(0, 0) }
    val fallbackWidth = with(LocalDensity.current) { 400.dp.roundToPx() }
    CompositionLocalProvider(LocalWorkspacePaneActive provides visible) {
        Layout(content = content, modifier = modifier.fillMaxHeight()
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})) { children, constraints ->
            val childWidth = if (visible) constraints.maxWidth else lastSize[0].takeIf { it > 0 } ?: fallbackWidth
            val childHeight = if (visible) constraints.maxHeight else lastSize[1].takeIf { it > 0 } ?: 240.dp.roundToPx()
            if (visible) { lastSize[0] = childWidth; lastSize[1] = childHeight }
            val childConstraints = Constraints.fixed(childWidth, childHeight)
            val placeables = children.map { it.measure(childConstraints) }
            layout(if (visible) constraints.maxWidth else 0, constraints.maxHeight) {
                if (visible) placeables.forEach { it.placeRelative(0, 0) }
            }
        }
    }
}
