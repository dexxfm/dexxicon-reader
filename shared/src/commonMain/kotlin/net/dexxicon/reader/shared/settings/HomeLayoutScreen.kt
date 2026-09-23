package net.dexxicon.reader.shared.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import net.dexxicon.reader.core.model.HomeLayout
import net.dexxicon.reader.core.model.HomeSection
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.home.title

/**
 * Settings › Arrange Home (issue #255): drag Home's shelves into any order and switch any of
 * them off. Same long-press drag handle as the Servers list (`App.kt`'s `ReorderableServers`),
 * plus "Move up"/"Move down" accessibility actions on each row, since a drag gesture alone
 * isn't reachable from a screen reader.
 */
@Composable
fun HomeLayoutScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { SettingsState(container, scope) }
    val prefs by state.preferences.collectAsState()
    DefaultsScaffold("Arrange Home", onBack) {
        Text(
            "Press and hold a handle to drag a shelf. A shelf that's switched off stays off " +
                "Home until you switch it back on; an empty one never shows either way.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ReorderableSections(layout = prefs.homeLayout, onChange = state::setHomeLayout)
    }
}

@Composable
private fun ReorderableSections(layout: HomeLayout, onChange: (HomeLayout) -> Unit) {
    // A local copy so the drag reflows instantly; re-synced from upstream when not dragging.
    var order by remember(layout) { mutableStateOf(layout.order) }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragDelta by remember { mutableStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<HomeSection, Int>() }

    Column(Modifier.fillMaxWidth()) {
        order.forEachIndexed { index, section ->
            val dragging = dragIndex == index
            val shown = section !in layout.hidden
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { rowHeights[section] = it.height }
                    .zIndex(if (dragging) 1f else 0f)
                    .offset { IntOffset(0, if (dragging) dragDelta.roundToInt() else 0) }
                    .then(if (dragging) Modifier.shadow(6.dp) else Modifier)
                    .background(
                        if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surface,
                    )
                    .semantics {
                        customActions = buildList {
                            if (index > 0) {
                                add(CustomAccessibilityAction("Move up") { onChange(layout.moved(index, index - 1)); true })
                            }
                            if (index < order.lastIndex) {
                                add(CustomAccessibilityAction("Move down") { onChange(layout.moved(index, index + 1)); true })
                            }
                        }
                    },
            ) {
                ListItem(
                    headlineContent = { Text(section.title) },
                    supportingContent = if (shown) null else {
                        { Text("Hidden from Home") }
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.pointerInput(order.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragIndex = index; dragDelta = 0f },
                                    onDragEnd = {
                                        if (dragIndex != null) onChange(layout.copy(order = order))
                                        dragIndex = null
                                        dragDelta = 0f
                                    },
                                    onDragCancel = {
                                        order = layout.order
                                        dragIndex = null
                                        dragDelta = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val cur = dragIndex ?: return@detectDragGesturesAfterLongPress
                                        dragDelta += amount.y
                                        val h = rowHeights[order[cur]] ?: return@detectDragGesturesAfterLongPress
                                        if (dragDelta > h / 2 && cur < order.lastIndex) {
                                            order = order.toMutableList().apply { add(cur + 1, removeAt(cur)) }
                                            dragIndex = cur + 1
                                            dragDelta -= h
                                        } else if (dragDelta < -h / 2 && cur > 0) {
                                            order = order.toMutableList().apply { add(cur - 1, removeAt(cur)) }
                                            dragIndex = cur - 1
                                            dragDelta += h
                                        }
                                    },
                                )
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = shown,
                            onCheckedChange = { onChange(layout.withVisibility(section, it)) },
                        )
                    },
                )
            }
        }
    }
}
